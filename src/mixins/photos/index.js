// Photos metadata lives beside the files, never inside them:
//   <user>/.apps/photos/data/sources.json
//   <user>/.apps/photos/data/tags.json
//   <user>/.apps/photos/data/albums/<id>.json
//   <user>/.apps/photos/data/shares.json
//   <user>/.apps/photos/data/outbox/<recipient>/albums.json
//
// An album is shared by granting read access to the album file and to every file it
// contains. shares.json is private: it records which access this app granted so a
// recipient can be removed without taking away access that came from somewhere else.

// One metadata write at a time across the tabs of this browser. The Web Locks API
// serializes the tabs; the in-memory queue covers browsers without it.
// shortcut: another device can still overwrite this one; upgrade to a versioned write
// if editing the same album from several devices becomes common.
let photoWriteQueue = Promise.resolve();
function withPhotoWriteLock(work) {
    if (typeof navigator !== "undefined" && navigator.locks != null)
        return navigator.locks.request("peergos-photos-metadata", work);
    let run = photoWriteQueue.then(work, work);
    photoWriteQueue = run.then(() => {}, () => {});
    return run;
}

module.exports = {
    data: function() {
        return {
            photosApp: null,
        };
    },
    methods: {
        photosCall: function(future) {
            return new Promise((resolve, reject) => {
                future.thenApply(res => resolve(res)).exceptionally(t => {
                    reject(t);
                    return null;
                });
            });
        },
        getPhotosApp: async function() {
            if (this.photosApp == null)
                this.photosApp = await this.photosCall(peergos.shared.user.App.init(this.context, "photos"));
            return this.photosApp;
        },
        photosPath: function(parts) {
            return peergos.client.PathUtils.directoryToPath(parts);
        },
        normalizePhotoPath: function(path) {
            if (path == null)
                return "";
            let p = String(path).replace(/\\/g, "/");
            while (p.startsWith("/"))
                p = p.substring(1);
            while (p.endsWith("/"))
                p = p.substring(0, p.length - 1);
            return p;
        },
        photosExists: async function(parts, owner) {
            let app = await this.getPhotosApp();
            return await this.photosCall(app.existsInternal(this.photosPath(parts), owner == null ? null : owner));
        },
        photosRead: async function(parts, owner) {
            let app = await this.getPhotosApp();
            let data = await this.photosCall(app.readInternal(this.photosPath(parts), owner == null ? null : owner));
            return new TextDecoder().decode(new Uint8Array(data));
        },
        photosWrite: async function(parts, text) {
            let app = await this.getPhotosApp();
            let bytes = convertToByteArray(new TextEncoder().encode(text));
            return await this.photosCall(app.writeInternal(this.photosPath(parts), bytes, null));
        },
        photosDelete: async function(parts) {
            let app = await this.getPhotosApp();
            return await this.photosCall(app.deleteInternal(this.photosPath(parts), null));
        },
        readPhotoJson: async function(parts, owner, fallback) {
            // Only a file that is not there falls back. A read that fails, or a file that
            // will not parse, is raised rather than treated as empty and written over.
            let exists = await this.photosExists(parts, owner);
            if (exists != 0)
                return fallback;
            return JSON.parse(await this.photosRead(parts, owner));
        },
        writePhotoJson: async function(parts, value) {
            return await this.photosWrite(parts, JSON.stringify(value));
        },

        loadPhotoSources: async function() {
            let data = await this.readPhotoJson(["sources.json"], null, {version: 1, folders: []});
            let folders = data != null && Array.isArray(data.folders) ? data.folders : [];
            return folders.map(f => this.normalizePhotoPath(f)).filter(f => f.length > 0);
        },
        savePhotoSources: async function(folders) {
            let unique = [];
            folders.forEach(f => {
                let p = this.normalizePhotoPath(f);
                if (p.length > 0 && !unique.includes(p))
                    unique.push(p);
            });
            await this.writePhotoJson(["sources.json"], {version: 1, folders: unique});
            return unique;
        },

        loadPhotoTags: async function() {
            let data = await this.readPhotoJson(["tags.json"], null, {version: 1, tags: {}});
            let tags = data != null && data.tags != null && typeof data.tags == "object" ? data.tags : {};
            let normalized = {};
            Object.keys(tags).forEach(path => {
                let p = this.normalizePhotoPath(path);
                let list = Array.isArray(tags[path]) ? tags[path].filter(t => typeof t == "string" && t.trim().length > 0) : [];
                if (p.length > 0 && list.length > 0)
                    normalized[p] = Array.from(new Set(list.map(t => t.trim())));
            });
            return normalized;
        },
        savePhotoTags: async function(tags) {
            await this.writePhotoJson(["tags.json"], {version: 1, tags: tags});
        },
        addPhotoTags: function(paths, newTags) {
            return withPhotoWriteLock(async () => {
                let tags = await this.loadPhotoTags();
                let wanted = newTags.map(t => String(t).trim()).filter(t => t.length > 0);
                paths.forEach(raw => {
                    let p = this.normalizePhotoPath(raw);
                    if (p.length == 0)
                        return;
                    let current = tags[p] == null ? [] : tags[p];
                    wanted.forEach(t => {
                        if (!current.includes(t))
                            current.push(t);
                    });
                    if (current.length > 0)
                        tags[p] = current;
                });
                await this.savePhotoTags(tags);
                return tags;
            });
        },
        removePhotoTags: function(paths, toRemove) {
            return withPhotoWriteLock(async () => {
                let tags = await this.loadPhotoTags();
                let unwanted = toRemove.map(t => String(t).trim());
                paths.forEach(raw => {
                    let p = this.normalizePhotoPath(raw);
                    if (tags[p] == null)
                        return;
                    tags[p] = tags[p].filter(t => !unwanted.includes(t));
                    if (tags[p].length == 0)
                        delete tags[p];
                });
                await this.savePhotoTags(tags);
                return tags;
            });
        },

        newPhotoAlbumId: function() {
            return Date.now().toString(36) + "-" + Math.random().toString(36).substring(2, 10);
        },
        albumPath: function(id) {
            return this.context.username + "/.apps/photos/data/albums/" + id + ".json";
        },
        outboxPath: function(recipient) {
            return this.context.username + "/.apps/photos/data/outbox/" + recipient;
        },
        loadPhotoAlbums: async function() {
            let app = await this.getPhotosApp();
            let names = [];
            try {
                names = (await this.photosCall(app.dirInternal(this.photosPath(["albums"]), null))).toArray();
            } catch (e) {
                names = [];
            }
            let albums = [];
            for (let name of names) {
                if (! name.endsWith(".json"))
                    continue;
                let id = name.substring(0, name.length - ".json".length);
                let data;
                try {
                    data = await this.readPhotoJson(["albums", name], null, null);
                } catch (e) {
                    console.warn("Could not read album " + name, e);
                    continue;
                }
                if (data == null)
                    continue;
                let items = Array.isArray(data.items) ? data.items.map(p => this.normalizePhotoPath(p)).filter(p => p.length > 0) : [];
                albums.push({
                    id: id,
                    name: typeof data.name == "string" && data.name.trim().length > 0 ? data.name : id,
                    items: Array.from(new Set(items)),
                });
            }
            albums.sort((a, b) => a.name.localeCompare(b.name));
            return albums;
        },
        savePhotoAlbum: async function(album) {
            await this.writePhotoJson(["albums", album.id + ".json"], {
                version: 1,
                name: album.name,
                items: album.items,
            });
        },
        createPhotoAlbum: async function(name, items) {
            let album = {
                id: this.newPhotoAlbumId(),
                name: String(name).trim(),
                items: Array.from(new Set((items || []).map(p => this.normalizePhotoPath(p)).filter(p => p.length > 0))),
            };
            await this.savePhotoAlbum(album);
            return album;
        },
        setPhotoAlbumName: function(album, name) {
            let trimmed = String(name).trim();
            if (trimmed.length == 0)
                return Promise.resolve(album);
            return withPhotoWriteLock(async () => {
                let updated = {id: album.id, name: trimmed, items: album.items};
                await this.savePhotoAlbum(updated);
                let shares = await this.loadPhotoShares();
                let entries = shares.albums[album.id] || {};
                for (let recipient of Object.keys(entries))
                    await this.updateOutboxIndex(recipient, updated, false);
                return updated;
            });
        },
        addItemsToAlbum: function(albumId, paths) {
            return withPhotoWriteLock(async () => {
                let albums = await this.loadPhotoAlbums();
                let album = albums.find(a => a.id == albumId);
                if (album == null)
                    throw new Error("Album not found");
                let added = [];
                paths.map(p => this.normalizePhotoPath(p)).forEach(p => {
                    if (p.length > 0 && !album.items.includes(p))
                        added.push(p);
                });
                if (added.length == 0)
                    return album;

                // Files added to a shared album are shared with its existing recipients too.
                // Share before saving the album: a failed share then leaves the items out, and
                // the same Add to album action can simply be retried.
                let shares = await this.loadPhotoShares();
                let entries = shares.albums[album.id] || {};
                let failures = [];
                for (let recipient of Object.keys(entries)) {
                    let entry = entries[recipient];
                    if (entry.files == null)
                        entry.files = [];
                    if (entry.preexisting == null)
                        entry.preexisting = [];
                    for (let path of added) {
                        try {
                            await this.shareAlbumItemWithRecipient(shares, album.id, recipient, entry, path);
                        } catch (e) {
                            failures.push(recipient + ": " + this.photoMessage(e));
                        }
                    }
                    entries[recipient] = entry;
                    shares.albums[album.id] = entries;
                    await this.savePhotoShares(shares);
                }
                if (failures.length > 0)
                    throw new Error("Could not share the added files: " + failures.join("; "));
                album.items = album.items.concat(added);
                await this.savePhotoAlbum(album);
                return album;
            });
        },
        removeItemFromAlbum: function(albumId, path) {
            return withPhotoWriteLock(async () => {
                let albums = await this.loadPhotoAlbums();
                let album = albums.find(a => a.id == albumId);
                if (album == null)
                    return;
                let target = this.normalizePhotoPath(path);

                // Revoke first. If one recipient could not be revoked the album keeps the
                // item, so the same removal can be tried again without losing the ledger.
                let shares = await this.loadPhotoShares();
                let entries = shares.albums[album.id] || {};
                let failures = [];
                for (let recipient of Object.keys(entries)) {
                    let entry = entries[recipient];
                    let recorded = (entry.files || []).includes(target);
                    let preexisting = (entry.preexisting || []).includes(target);
                    if (recorded && !preexisting && !this.albumListHasFile(shares, recipient, album.id, target)) {
                        try {
                            await this.unsharePhotoPath(target, recipient);
                        } catch (e) {
                            failures.push(recipient + ": " + this.photoMessage(e));
                            continue;
                        }
                    }
                    entry.files = (entry.files || []).filter(p => p != target);
                    entry.preexisting = (entry.preexisting || []).filter(p => p != target);
                    entries[recipient] = entry;
                }
                await this.savePhotoShares(shares);
                if (failures.length > 0)
                    throw new Error("Could not remove access to " + target + ": " + failures.join("; "));
                album.items = album.items.filter(p => p != target);
                await this.savePhotoAlbum(album);
                return album;
            });
        },
        deletePhotoAlbum: function(album) {
            return withPhotoWriteLock(async () => {
                let shares = await this.loadPhotoShares();
                let entries = shares.albums[album.id] || {};
                let failures = [];
                try {
                    for (let recipient of Object.keys(entries))
                        failures = failures.concat(await this.unsharePhotoAlbumInternal(album, recipient, shares));
                } finally {
                    await this.savePhotoShares(shares);
                }
                if (failures.length > 0)
                    throw new Error("Could not revoke every recipient: " + failures.join("; "));
                await this.photosDelete(["albums", album.id + ".json"]);
            });
        },

        loadPhotoShares: async function() {
            let data = await this.readPhotoJson(["shares.json"], null, {version: 1, albums: {}});
            if (data == null || typeof data != "object")
                data = {version: 1, albums: {}};
            if (data.albums == null || typeof data.albums != "object")
                data.albums = {};
            Object.keys(data.albums).forEach(id => {
                let recipients = data.albums[id];
                if (recipients == null || typeof recipients != "object") {
                    data.albums[id] = {};
                    return;
                }
                Object.keys(recipients).forEach(recipient => {
                    let entry = recipients[recipient];
                    if (entry == null || typeof entry != "object") {
                        recipients[recipient] = {files: [], preexisting: []};
                    } else {
                        if (!Array.isArray(entry.files))
                            entry.files = [];
                        if (!Array.isArray(entry.preexisting))
                            entry.preexisting = [];
                    }
                });
            });
            return data;
        },
        savePhotoShares: async function(shares) {
            await this.writePhotoJson(["shares.json"], shares);
        },
        albumListHasFile: function(shares, recipient, exceptAlbumId, path) {
            let target = this.normalizePhotoPath(path);
            for (let id of Object.keys(shares.albums || {})) {
                if (id == exceptAlbumId)
                    continue;
                let entry = shares.albums[id][recipient];
                if (entry != null && (entry.files || []).includes(target))
                    return true;
            }
            return false;
        },
        photoUserHasAccess: async function(path, recipient) {
            let state = await this.photosCall(this.context.sharedWith(this.photosPath(this.normalizePhotoPath(path).split("/"))));
            let readers = state.readAccess.toArray([]);
            let writers = state.writeAccess.toArray([]);
            return readers.includes(recipient) || writers.includes(recipient);
        },
        sharePhotoPath: async function(path, recipient) {
            await this.photosCall(this.context.shareReadAccessWith(
                this.photosPath(this.normalizePhotoPath(path).split("/")),
                peergos.client.JsUtil.asSet([recipient])));
        },
        unsharePhotoPath: async function(path, recipient) {
            await this.photosCall(this.context.unShareReadAccessWith(
                this.photosPath(this.normalizePhotoPath(path).split("/")),
                peergos.client.JsUtil.asSet([recipient])));
        },
        ensurePhotoShared: async function(path, recipient) {
            if (await this.photoUserHasAccess(path, recipient))
                return false;
            await this.sharePhotoPath(path, recipient);
            return true;
        },
        ensurePhotoOutbox: async function(recipient) {
            let exists = await this.photosExists(["outbox", recipient]);
            if (exists != 1)
                await this.photosCall((await this.getPhotosApp()).createDirectoryInternal(this.photosPath(["outbox", recipient]), null));
            await this.ensurePhotoShared(this.outboxPath(recipient), recipient);
        },
        updateOutboxIndex: async function(recipient, album, remove) {
            let current = await this.readPhotoJson(["outbox", recipient, "albums.json"], null, {albums: []});
            let albums = current != null && Array.isArray(current.albums) ? current.albums : [];
            albums = albums.filter(a => a != null && a.id != album.id);
            if (!remove)
                albums.push({id: album.id, name: album.name});
            albums.sort((a, b) => String(a.name || "").localeCompare(String(b.name || "")));
            await this.writePhotoJson(["outbox", recipient, "albums.json"], {albums: albums});
        },
        // Records what sharing one album item with one recipient did, so removing them
        // later takes back only the access this album gave.
        shareAlbumItemWithRecipient: async function(shares, albumId, recipient, entry, path) {
            // Only the owner of a file can grant access to it.
            if (path.split("/")[0] != this.context.username)
                return;
            let access;
            try {
                access = await this.photoUserHasAccess(path, recipient);
            } catch (e) {
                // Moved or deleted since it was put in the album: nothing to share.
                console.warn("Could not check album access to " + path, e);
                return;
            }
            let fromOtherAlbum = this.albumListHasFile(shares, recipient, albumId, path);
            if (!access) {
                await this.sharePhotoPath(path, recipient);
            } else if (!fromOtherAlbum && !entry.files.includes(path) && !entry.preexisting.includes(path)) {
                // Already readable before this album: recording it keeps a later removal
                // from taking access away that came from somewhere else.
                entry.preexisting.push(path);
            }
            if (!entry.files.includes(path))
                entry.files.push(path);
        },
        sharePhotoAlbum: function(album, recipients) {
            return withPhotoWriteLock(async () => {
                let shares = await this.loadPhotoShares();
                let entries = shares.albums[album.id] || {};
                let failures = [];
                for (let recipient of recipients) {
                    let entry = entries[recipient];
                    if (entry == null)
                        entry = {files: [], preexisting: []};
                    entries[recipient] = entry;
                    shares.albums[album.id] = entries;
                    let failed = false;
                    try {
                        await this.ensurePhotoOutbox(recipient);
                    } catch (e) {
                        failed = true;
                        failures.push(recipient + ": " + this.photoMessage(e));
                    }
                    if (!failed) {
                        for (let path of album.items) {
                            try {
                                await this.shareAlbumItemWithRecipient(shares, album.id, recipient, entry, path);
                            } catch (e) {
                                failed = true;
                                failures.push(recipient + ": " + this.photoMessage(e));
                            }
                        }
                    }
                    // Nothing was granted, so leave no recipient entry behind: it would
                    // hide the retry from the share dialog.
                    if (failed && entry.files.length == 0 && entry.preexisting.length == 0) {
                        delete entries[recipient];
                        if (Object.keys(entries).length == 0)
                            delete shares.albums[album.id];
                    }
                    // Save what did work, so a later removal can still take it back.
                    await this.savePhotoShares(shares);
                    if (failed)
                        continue;
                    try {
                        await this.ensurePhotoShared(this.albumPath(album.id), recipient);
                        // Last: until it is written the recipient has the album but cannot find it.
                        await this.updateOutboxIndex(recipient, album, false);
                    } catch (e) {
                        failures.push(recipient + ": " + this.photoMessage(e));
                    }
                }
                if (failures.length > 0)
                    throw new Error("Could not share the whole album: " + failures.join("; "));
            });
        },
        unsharePhotoAlbumInternal: async function(album, recipient, shares) {
            let entry = shares.albums[album.id] == null ? null : shares.albums[album.id][recipient];
            if (entry == null)
                return [];
            let failures = [];
            // The album comes first: once its metadata is gone, the item paths it lists
            // are no longer handed to the recipient even if a file below fails to revoke.
            try {
                await this.unsharePhotoPath(this.albumPath(album.id), recipient);
            } catch (e) {
                failures.push(recipient + " album: " + this.photoMessage(e));
            }
            for (let path of (entry.files || []).slice()) {
                let preexisting = (entry.preexisting || []).includes(path);
                let sharedElsewhere = this.albumListHasFile(shares, recipient, album.id, path);
                if (preexisting || sharedElsewhere) {
                    entry.files = (entry.files || []).filter(p => p != path);
                    entry.preexisting = (entry.preexisting || []).filter(p => p != path);
                    continue;
                }
                try {
                    await this.unsharePhotoPath(path, recipient);
                    entry.files = (entry.files || []).filter(p => p != path);
                } catch (e) {
                    failures.push(recipient + " " + path + ": " + this.photoMessage(e));
                }
            }
            // Keep the entry and the outbox index when anything failed: they are what a
            // retry needs, and the recipient may still have access until it succeeds.
            if (failures.length == 0) {
                await this.updateOutboxIndex(recipient, album, true);
                delete shares.albums[album.id][recipient];
                if (Object.keys(shares.albums[album.id]).length == 0)
                    delete shares.albums[album.id];
            }
            return failures;
        },
        removeRecipientFromAlbum: function(album, recipient) {
            return withPhotoWriteLock(async () => {
                let shares = await this.loadPhotoShares();
                let failures;
                try {
                    failures = await this.unsharePhotoAlbumInternal(album, recipient, shares);
                } finally {
                    await this.savePhotoShares(shares);
                }
                if (failures.length > 0)
                    throw new Error("Could not remove " + recipient + " from the album: " + failures.join("; "));
            });
        },
        loadIncomingPhotoAlbums: async function() {
            let me = this.context.username;
            let following = (await this.photosCall(this.context.getFollowing())).toArray([]);
            let found = [];
            await Promise.all(following.map(async owner => {
                try {
                    let exists = await this.photosExists(["outbox", me, "albums.json"], owner);
                    if (exists != 0)
                        return;
                    let index = JSON.parse(await this.photosRead(["outbox", me, "albums.json"], owner));
                    let listed = index != null && Array.isArray(index.albums) ? index.albums : [];
                    for (let item of listed) {
                        if (item == null || item.id == null)
                            continue;
                        try {
                            let data = await this.readPhotoJson(["albums", item.id + ".json"], owner, null);
                            if (data == null)
                                continue;
                            let items = Array.isArray(data.items) ? data.items.map(p => this.normalizePhotoPath(p)).filter(p => p.length > 0) : [];
                            found.push({
                                id: item.id,
                                name: typeof data.name == "string" ? data.name : item.name,
                                items: Array.from(new Set(items)),
                                owner: owner,
                            });
                        } catch (e) {
                            console.warn("Could not read album " + item.id + " from " + owner, e);
                        }
                    }
                } catch (e) {
                    console.warn("Could not read shared albums from " + owner, e);
                }
            }));
            found.sort((a, b) => (a.owner + "/" + a.name).localeCompare(b.owner + "/" + b.name));
            return found;
        },
        resolvePhotoItems: async function(paths) {
            let resolved = await Promise.all((paths || []).map(async raw => {
                let path = this.normalizePhotoPath(raw);
                try {
                    let opt = await this.photosCall(this.context.getByPath(path));
                    if (opt.ref == null)
                        return {path: path, file: null, missing: true};
                    return {path: path, file: opt.get(), missing: false};
                } catch (e) {
                    return {path: path, file: null, missing: true};
                }
            }));
            return resolved;
        },
        isPhotoImage: function(file) {
            try {
                return (file.getFileProperties().mimeType || "").startsWith("image/");
            } catch (e) {
                return false;
            }
        },
        photoThumbnail: function(file) {
            try {
                return file.getBase64Thumbnail() || "";
            } catch (e) {
                return "";
            }
        },
        photoFileName: function(item) {
            if (item == null)
                return "";
            if (item.file != null)
                return item.file.getFileProperties().name;
            let slash = item.path.lastIndexOf("/");
            return slash < 0 ? item.path : item.path.substring(slash + 1);
        },
        photoMessage: function(throwable) {
            if (throwable == null)
                return "";
            return throwable.message != null ? throwable.message : (throwable.detailMessage != null ? throwable.detailMessage : "" + throwable);
        },
    },
};
