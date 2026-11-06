<template>
<article class="app-view pg-view photos-view">
    <AppHeader>
        <template #primary>
            <h1>{{ translate("APPNAV.PHOTOS") }}</h1>
        </template>
    </AppHeader>
    <main>
        <Spinner v-if="showSpinner"></Spinner>

        <nav class="photos-tabs" role="tablist">
            <button type="button" role="tab" :aria-selected="tab == 'all'" :class="{ 'is-active': tab == 'all' }" @click="switchTab('all')">
                {{ translate("PHOTOS.ALL") }}
            </button>
            <button type="button" role="tab" :aria-selected="tab == 'albums'" :class="{ 'is-active': tab == 'albums' }" @click="switchTab('albums')">
                {{ translate("PHOTOS.ALBUMS") }}
            </button>
            <button type="button" role="tab" :aria-selected="tab == 'tags'" :class="{ 'is-active': tab == 'tags' }" @click="switchTab('tags')">
                {{ translate("PHOTOS.TAGS") }}
            </button>
            <button type="button" role="tab" :aria-selected="tab == 'shared'" :class="{ 'is-active': tab == 'shared' }" @click="switchTab('shared')">
                {{ translate("PHOTOS.SHARED.WITH.ME") }}
            </button>
        </nav>

        <section v-if="selectedPaths.length > 0" class="photos-selection">
            <span class="photos-selection__count">{{ selectedPaths.length }} {{ translate("PHOTOS.SELECTED") }}</span>
            <button type="button" class="pg-btn" @click="showAlbumPicker = true">{{ translate("PHOTOS.ADD.TO.ALBUM") }}</button>
            <button type="button" class="pg-btn" @click="showTagEditor = true">{{ translate("PHOTOS.TAGS") }}</button>
            <button v-if="currentAlbum != null && albumIsOwn" type="button" class="pg-btn pg-btn--danger" @click="removeSelectedFromAlbum">
                {{ translate("PHOTOS.REMOVE.FROM.ALBUM") }}
            </button>
            <button type="button" class="pg-btn" @click="selectedPaths = []">{{ translate("DRIVE.DESELECT") }}</button>
        </section>

        <!-- All photos: what the chosen source folders hold -->
        <template v-if="tab == 'all'">
            <section class="photos-toolbar">
                <div class="photos-toolbar__folders">
                    <button type="button" class="pg-btn" @click="showFolderPicker = true">
                        {{ sources.length == 0 ? translate("PHOTOS.CHOOSE.FOLDERS") : translate("PHOTOS.CHANGE.FOLDERS") }}
                    </button>
                    <span v-for="folder in sources" :key="folder" class="pg-chip" :title="folder">{{ pathLeaf(folder) }}</span>
                </div>
                <button type="button" class="pg-btn" :disabled="loadingPhotos || sources.length == 0" @click="loadAllPhotos">
                    {{ translate("PHOTOS.REFRESH") }}
                </button>
            </section>

            <section v-if="sources.length == 0" class="pg-empty">
                <span class="pg-empty__mark" aria-hidden="true"><AppIcon icon="photos"/></span>
                <h2>{{ translate("PHOTOS.NO.SOURCES.TITLE") }}</h2>
                <p>{{ translate("PHOTOS.NO.SOURCES.BODY") }}</p>
            </section>
            <p v-else-if="loadingPhotos" class="pg-note">{{ translate("PHOTOS.LOADING") }}</p>
            <section v-else-if="allPhotos.length == 0" class="pg-empty">
                <span class="pg-empty__mark" aria-hidden="true"><AppIcon icon="photos"/></span>
                <h2>{{ translate("PHOTOS.NO.PHOTOS.TITLE") }}</h2>
                <p>{{ translate("PHOTOS.NO.PHOTOS.BODY") }}</p>
            </section>
            <DriveGrid v-else>
                <DriveGridCard
                    v-for="(item, index) in allPhotos"
                    :key="item.path"
                    :filename="item.name"
                    :src="item.thumbnail"
                    :type="item.type"
                    :file="item.file"
                    :itemIndex="index"
                    :selected="selectedPaths.includes(item.path)"
                    :selecting="selectedPaths.length > 0"
                    :menuStandsDown="menuStandsDown"
                    :dragstartFunc="nothing"
                    :dropFunc="nothing"
                    @click="onItemClick(item)"
                    @toggleSelection="togglePhotoSelection(item.path)"
                />
            </DriveGrid>
        </template>

        <!-- Albums: the owner's own, then one opened -->
        <template v-if="tab == 'albums'">
            <section v-if="currentAlbum == null">
                <section class="photos-toolbar">
                    <button type="button" class="pg-btn pg-btn--primary" @click="newAlbum">{{ translate("PHOTOS.NEW.ALBUM") }}</button>
                </section>
                <ul v-if="albums.length > 0" class="photos-list">
                    <li v-for="album in albums" :key="album.id">
                        <button type="button" class="photos-list__item" @click="openAlbum(album)">
                            <span class="photos-list__name"><AppIcon icon="photos"/>{{ album.name }}</span>
                            <span class="photos-list__count">{{ album.items.length }} {{ translate("PHOTOS.ITEMS") }}</span>
                        </button>
                    </li>
                </ul>
                <section v-else class="pg-empty">
                    <span class="pg-empty__mark" aria-hidden="true"><AppIcon icon="photos"/></span>
                    <h2>{{ translate("PHOTOS.NO.ALBUMS.TITLE") }}</h2>
                    <p>{{ translate("PHOTOS.NO.ALBUMS.BODY") }}</p>
                </section>
            </section>
            <template v-else>
                <section class="photos-album-head">
                    <button type="button" class="pg-btn" @click="closeAlbum">&larr; {{ translate("PHOTOS.ALBUMS") }}</button>
                    <h2>{{ currentAlbum.name }}</h2>
                    <span class="pg-note">{{ currentAlbumItems.length }} {{ translate("PHOTOS.ITEMS") }}</span>
                    <div v-if="albumIsOwn" class="photos-album-head__actions">
                        <button type="button" class="pg-btn" @click="renameAlbum">{{ translate("PHOTOS.RENAME") }}</button>
                        <button type="button" class="pg-btn" @click="shareAlbum">{{ translate("PHOTOS.SHARE.ALBUM") }}</button>
                        <button type="button" class="pg-btn pg-btn--danger" @click="askDeleteAlbum">{{ translate("PHOTOS.DELETE") }}</button>
                    </div>
                    <span v-else class="pg-note">{{ translate("PHOTOS.SHARED.BY").replace("$NAME", currentAlbum.owner) }}</span>
                </section>
                <div v-if="missingAlbumPaths.length > 0" class="pg-note photos-missing">
                    <span>{{ translate("PHOTOS.UNAVAILABLE").replace("$COUNT", missingAlbumPaths.length) }}</span>
                </div>
                <section v-if="currentAlbumItems.length == 0" class="pg-empty">
                    <h2>{{ translate("PHOTOS.EMPTY.ALBUM.TITLE") }}</h2>
                    <p>{{ translate("PHOTOS.EMPTY.ALBUM.BODY") }}</p>
                </section>
                <DriveGrid v-else>
                    <DriveGridCard
                        v-for="(item, index) in currentAlbumItems"
                        :key="item.path"
                        :filename="item.name"
                        :src="item.thumbnail"
                        :type="item.type"
                        :file="item.file"
                        :itemIndex="index"
                        :selected="!item.missing && selectedPaths.includes(item.path)"
                        :selecting="selectedPaths.length > 0"
                        :menuStandsDown="menuStandsDown"
                        :missing-label="item.missing ? translate('PHOTOS.MISSING') : ''"
                        :dragstartFunc="nothing"
                        :dropFunc="nothing"
                        @click="onItemClick(item)"
                        @toggleSelection="togglePhotoSelection(item.path, item.missing)"
                    />
                </DriveGrid>
            </template>
        </template>

        <!-- Tags: everything tagged, narrowed to one tag at a time -->
        <template v-if="tab == 'tags'">
            <div v-if="tagNames.length > 0" class="pg-chips photos-tag-filter">
                <button type="button" class="pg-chip photos-tag-filter__item" :class="{ 'pg-chip--on': tagFilter.length == 0 }" @click="tagFilter = ''">
                    {{ translate("PHOTOS.ALL") }} ({{ taggedItems.length }})
                </button>
                <button v-for="tag in tagNames" :key="tag" type="button" class="pg-chip photos-tag-filter__item" :class="{ 'pg-chip--on': tagFilter == tag }" @click="tagFilter = tag">
                    {{ tag }} ({{ tagCount(tag) }})
                </button>
            </div>
            <div v-if="missingTaggedPaths.length > 0" class="pg-note photos-missing">
                <span>{{ translate("PHOTOS.UNAVAILABLE").replace("$COUNT", missingTaggedPaths.length) }}</span>
            </div>
            <section v-if="tagNames.length == 0" class="pg-empty">
                <span class="pg-empty__mark" aria-hidden="true"><AppIcon icon="photos"/></span>
                <h2>{{ translate("PHOTOS.NO.TAGS.TITLE") }}</h2>
                <p>{{ translate("PHOTOS.NO.TAGS.BODY") }}</p>
            </section>
            <section v-else-if="tagFilteredItems.length == 0" class="pg-empty">
                <h2>{{ translate("PHOTOS.NO.TAG.ITEMS.TITLE") }}</h2>
            </section>
            <DriveGrid v-else>
                <DriveGridCard
                    v-for="(item, index) in tagFilteredItems"
                    :key="item.path"
                    :filename="item.name"
                    :src="item.thumbnail"
                    :type="item.type"
                    :file="item.file"
                    :itemIndex="index"
                    :selected="!item.missing && selectedPaths.includes(item.path)"
                    :selecting="selectedPaths.length > 0"
                    :menuStandsDown="menuStandsDown"
                    :missing-label="item.missing ? translate('PHOTOS.MISSING') : ''"
                    :dragstartFunc="nothing"
                    :dropFunc="nothing"
                    @click="onItemClick(item)"
                    @toggleSelection="togglePhotoSelection(item.path, item.missing)"
                />
            </DriveGrid>
        </template>

        <!-- Shared with me: albums other people have shared -->
        <template v-if="tab == 'shared'">
            <section v-if="currentAlbum == null">
                <section class="photos-toolbar">
                    <button type="button" class="pg-btn" :disabled="loadingShared" @click="loadSharedAlbums">{{ translate("PHOTOS.REFRESH") }}</button>
                </section>
                <p v-if="loadingShared" class="pg-note">{{ translate("PHOTOS.LOADING") }}</p>
                <ul v-else-if="incomingAlbums.length > 0" class="photos-list">
                    <li v-for="album in incomingAlbums" :key="album.owner + '/' + album.id">
                        <button type="button" class="photos-list__item" @click="openIncomingAlbum(album)">
                            <span class="photos-list__name"><AppIcon icon="photos"/>{{ album.name }}</span>
                            <span class="photos-list__count">{{ album.owner }} &middot; {{ album.items.length }} {{ translate("PHOTOS.ITEMS") }}</span>
                        </button>
                    </li>
                </ul>
                <section v-else class="pg-empty">
                    <span class="pg-empty__mark" aria-hidden="true"><AppIcon icon="photos"/></span>
                    <h2>{{ translate("PHOTOS.NO.SHARED.TITLE") }}</h2>
                    <p>{{ translate("PHOTOS.NO.SHARED.BODY") }}</p>
                </section>
            </section>
            <template v-else>
                <section class="photos-album-head">
                    <button type="button" class="pg-btn" @click="closeAlbum">&larr; {{ translate("PHOTOS.SHARED.WITH.ME") }}</button>
                    <h2>{{ currentAlbum.name }}</h2>
                    <span class="pg-note">{{ currentAlbumItems.length }} {{ translate("PHOTOS.ITEMS") }}</span>
                    <span class="pg-note">{{ translate("PHOTOS.SHARED.BY").replace("$NAME", currentAlbum.owner) }}</span>
                </section>
                <div v-if="missingAlbumPaths.length > 0" class="pg-note photos-missing">
                    {{ translate("PHOTOS.UNAVAILABLE").replace("$COUNT", missingAlbumPaths.length) }}
                </div>
                <section v-if="currentAlbumItems.length == 0" class="pg-empty">
                    <h2>{{ translate("PHOTOS.EMPTY.ALBUM.TITLE") }}</h2>
                </section>
                <DriveGrid v-else>
                    <DriveGridCard
                        v-for="(item, index) in currentAlbumItems"
                        :key="item.path"
                        :filename="item.name"
                        :src="item.thumbnail"
                        :type="item.type"
                        :file="item.file"
                        :itemIndex="index"
                        :selected="!item.missing && selectedPaths.includes(item.path)"
                        :selecting="selectedPaths.length > 0"
                        :menuStandsDown="menuStandsDown"
                        :missing-label="item.missing ? translate('PHOTOS.MISSING') : ''"
                        :dragstartFunc="nothing"
                        :dropFunc="nothing"
                        @click="onItemClick(item)"
                        @toggleSelection="togglePhotoSelection(item.path, item.missing)"
                    />
                </DriveGrid>
            </template>
        </template>
    </main>

    <FolderPicker
        v-if="showFolderPicker"
        :baseFolder="'/' + context.username"
        :selectedFolder_func="foldersPicked"
        :multipleFolderSelection="true"
        :initiallySelectedPaths="selectedSourcePaths"
        :noDriveSelection="true"
        :pickerTitle="translate('PHOTOS.SOURCE.FOLDERS')"
    ></FolderPicker>
    <AlbumPicker v-if="showAlbumPicker" :paths="selectedPaths" @close="showAlbumPicker = false" @done="albumAdded"></AlbumPicker>
    <TagEditor v-if="showTagEditor" :paths="selectedPaths" @close="showTagEditor = false" @done="tagsChanged"></TagEditor>
    <AlbumShare v-if="sharingAlbum != null" :album="sharingAlbum" @close="sharingAlbum = null" @changed="sharingChanged"></AlbumShare>
    <Gallery v-if="showGallery" @hide-gallery="showGallery = false" :files="galleryFiles" :initial-index="galleryIndex"></Gallery>

    <AppPrompt
        v-if="showPrompt"
        @hide-prompt="showPrompt = false"
        :message="prompt_message"
        :placeholder="translate('PHOTOS.ALBUM.NAME.PLACEHOLDER')"
        :value="prompt_value"
        :action="prompt_action"
        :consumer_func="prompt_consumer_func">
    </AppPrompt>
    <Confirm
        v-if="showConfirm"
        @hide-confirm="showConfirm = false"
        :confirm_message="confirm_message"
        :confirm_body="confirm_body"
        :consumer_cancel_func="confirm_cancel_func"
        :consumer_func="confirm_consumer_func">
    </Confirm>
</article>
</template>

<script>
const AppHeader = require("../components/AppHeader.vue");
const AppIcon = require("../components/AppIcon.vue");
const Spinner = require("../components/spinner/Spinner.vue");
const FolderPicker = require("../components/picker/FolderPicker.vue");
const Confirm = require("../components/confirm/Confirm.vue");
const AppPrompt = require("../components/prompt/AppPrompt.vue");
const DriveGrid = require("../components/drive/DriveGrid.vue");
const DriveGridCard = require("../components/drive/DriveGridCard.vue");
const Gallery = require("../components/drive/DriveGallery.vue");
const AlbumPicker = require("../components/photos/AlbumPicker.vue");
const TagEditor = require("../components/photos/TagEditor.vue");
const AlbumShare = require("../components/photos/AlbumShare.vue");
const i18n = require("../i18n/index.js");
const paths = require("../mixins/paths/index.js");
const routerMixins = require("../mixins/router/index.js");
const photos = require("../mixins/photos/index.js");

module.exports = {
    components: {
        AppHeader,
        AppIcon,
        Spinner,
        FolderPicker,
        Confirm,
        AppPrompt,
        DriveGrid,
        DriveGridCard,
        Gallery,
        AlbumPicker,
        TagEditor,
        AlbumShare,
    },
    mixins: [i18n, paths, routerMixins, photos],
    data: function() {
        return {
            tab: "all",
            sources: [],
            allPhotos: [],
            loadingPhotos: false,
            albums: [],
            currentAlbum: null,
            currentAlbumItems: [],
            missingAlbumPaths: [],
            incomingAlbums: [],
            loadingShared: false,
            tags: {},
            taggedItems: [],
            missingTaggedPaths: [],
            tagFilter: "",
            selectedPaths: [],
            showFolderPicker: false,
            showAlbumPicker: false,
            showTagEditor: false,
            sharingAlbum: null,
            showGallery: false,
            galleryFiles: [],
            galleryIndex: 0,
            showSpinner: false,
            showPrompt: false,
            prompt_message: "",
            prompt_value: "",
            prompt_action: "",
            prompt_consumer_func: () => {},
            showConfirm: false,
            confirm_message: "",
            confirm_body: "",
            confirm_consumer_func: () => {},
            confirm_cancel_func: () => {},
        };
    },
    computed: {
        ...Vuex.mapState(["context"]),
        selectedSourcePaths: function() {
            return this.sources.map(folder => "/" + folder);
        },
        albumIsOwn: function() {
            return this.currentAlbum != null
                && (this.currentAlbum.owner == null || this.currentAlbum.owner == this.context.username);
        },
        tagNames: function() {
            let names = [];
            Object.keys(this.tags).forEach(path => (this.tags[path] || []).forEach(tag => {
                if (!names.includes(tag))
                    names.push(tag);
            }));
            return names.sort((a, b) => a.localeCompare(b));
        },
        tagFilteredItems: function() {
            if (this.tagFilter.length == 0)
                return this.taggedItems;
            return this.taggedItems.filter(item => (this.tags[item.path] || []).includes(this.tagFilter));
        },
    },
    mounted: async function() {
        await this.reloadSources();
        await Promise.all([this.loadAllPhotos(), this.loadAlbums(), this.loadTags()]);
    },
    methods: {
        switchTab: function(tab) {
            this.tab = tab;
            this.currentAlbum = null;
            this.selectedPaths = [];
            if (tab == "tags")
                this.loadTags();
            else if (tab == "shared")
                this.loadSharedAlbums();
        },
        reloadSources: async function() {
            try {
                this.sources = await this.loadPhotoSources();
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            }
        },
        foldersPicked: async function(folders) {
            this.showFolderPicker = false;
            this.showSpinner = true;
            try {
                this.sources = await this.savePhotoSources(folders);
                this.selectedPaths = [];
                await this.loadAllPhotos();
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            } finally {
                this.showSpinner = false;
            }
        },
        loadAllPhotos: async function() {
            if (this.sources.length == 0) {
                this.allPhotos = [];
                return;
            }
            this.loadingPhotos = true;
            let seen = {};
            let found = [];
            try {
                for (let folder of this.sources) {
                    try {
                        let opt = await this.photosCall(this.context.getByPath(folder));
                        if (opt.ref != null)
                            await this.scanPhotoFolder(opt.get(), folder, seen, found);
                    } catch (e) {
                        console.warn("Could not read photo source " + folder, e);
                    }
                }
                found.sort((a, b) => a.path.localeCompare(b.path));
                this.allPhotos = found;
            } finally {
                this.loadingPhotos = false;
            }
        },
        scanPhotoFolder: async function(folder, path, seen, found) {
            let children = (await this.photosCall(folder.getChildren(this.context.crypto.hasher, this.context.network))).toArray();
            for (let child of children) {
                let props = child.getFileProperties();
                let name = child.getName();
                if (name.startsWith(".") || props.isHidden)
                    continue;
                let childPath = path + "/" + name;
                if (props.isDirectory) {
                    await this.scanPhotoFolder(child, childPath, seen, found);
                } else if ((props.mimeType || "").startsWith("image/") && !seen[childPath]) {
                    seen[childPath] = true;
                    found.push(this.photoItem(childPath, child));
                }
            }
        },
        photoItem: function(path, file) {
            let props = file.getFileProperties();
            return {
                path: path,
                file: file,
                name: props.name,
                type: props.getType(),
                mime: props.mimeType,
                thumbnail: this.photoThumbnail(file),
                missing: false,
            };
        },
        missingPhotoItem: function(path) {
            return {
                path: path,
                file: null,
                name: this.photoFileName({path: path, file: null}),
                type: "file",
                mime: "",
                thumbnail: "",
                missing: true,
            };
        },
        loadAlbums: async function() {
            try {
                this.albums = await this.loadPhotoAlbums();
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            }
        },
        openAlbum: async function(album) {
            this.currentAlbum = album;
            this.selectedPaths = [];
            await this.loadAlbumItems(album);
        },
        openIncomingAlbum: async function(album) {
            this.currentAlbum = album;
            this.selectedPaths = [];
            await this.loadAlbumItems(album);
        },
        closeAlbum: function() {
            this.currentAlbum = null;
            this.currentAlbumItems = [];
            this.missingAlbumPaths = [];
            this.selectedPaths = [];
        },
        loadAlbumItems: async function(album) {
            let resolved = await this.resolvePhotoItems(album.items);
            this.currentAlbumItems = resolved
                .map(item => item.missing ? this.missingPhotoItem(item.path) : this.photoItem(item.path, item.file))
                .sort((a, b) => a.name.localeCompare(b.name));
            this.missingAlbumPaths = resolved.filter(item => item.missing).map(item => item.path);
        },
        loadTags: async function() {
            try {
                this.tags = await this.loadPhotoTags();
                let resolved = await this.resolvePhotoItems(Object.keys(this.tags));
                this.taggedItems = resolved
                    .map(item => item.missing ? this.missingPhotoItem(item.path) : this.photoItem(item.path, item.file))
                    .sort((a, b) => a.name.localeCompare(b.name));
                this.missingTaggedPaths = resolved.filter(item => item.missing).map(item => item.path);
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            }
        },
        loadSharedAlbums: async function() {
            this.loadingShared = true;
            try {
                this.incomingAlbums = await this.loadIncomingPhotoAlbums();
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            } finally {
                this.loadingShared = false;
            }
        },
        newAlbum: function() {
            this.prompt_message = this.translate("PHOTOS.NEW.ALBUM");
            this.prompt_value = "";
            this.prompt_action = this.translate("PHOTOS.CREATE.ALBUM");
            this.prompt_consumer_func = async name => {
                if (name == null || name.trim().length == 0)
                    return;
                this.showSpinner = true;
                try {
                    let album = await this.createPhotoAlbum(name, []);
                    await this.loadAlbums();
                    await this.openAlbum(album);
                } catch (e) {
                    this.$toast.error(this.photoMessage(e), {timeout: false});
                } finally {
                    this.showSpinner = false;
                }
            };
            this.showPrompt = true;
        },
        renameAlbum: function() {
            if (this.currentAlbum == null)
                return;
            this.prompt_message = this.translate("PHOTOS.RENAME");
            this.prompt_value = this.currentAlbum.name;
            this.prompt_action = this.translate("PHOTOS.RENAME");
            this.prompt_consumer_func = async name => {
                if (name == null || name.trim().length == 0)
                    return;
                this.showSpinner = true;
                try {
                    this.currentAlbum = await this.setPhotoAlbumName(this.currentAlbum, name);
                    await this.loadAlbums();
                } catch (e) {
                    this.$toast.error(this.photoMessage(e), {timeout: false});
                } finally {
                    this.showSpinner = false;
                }
            };
            this.showPrompt = true;
        },
        shareAlbum: function() {
            if (this.currentAlbum != null)
                this.sharingAlbum = this.currentAlbum;
        },
        sharingChanged: async function() {
            await this.loadAlbums();
        },
        askDeleteAlbum: function() {
            if (this.currentAlbum == null)
                return;
            this.confirm_message = this.translate("PHOTOS.DELETE.CONFIRM").replace("$NAME", this.currentAlbum.name);
            this.confirm_body = this.translate("PHOTOS.DELETE.BODY");
            this.confirm_consumer_func = async () => {
                this.showSpinner = true;
                try {
                    await this.deletePhotoAlbum(this.currentAlbum);
                    this.closeAlbum();
                    await this.loadAlbums();
                } catch (e) {
                    this.$toast.error(this.photoMessage(e), {timeout: false});
                } finally {
                    this.showSpinner = false;
                }
            };
            this.confirm_cancel_func = () => {};
            this.showConfirm = true;
        },
        albumAdded: async function(album) {
            this.showAlbumPicker = false;
            this.selectedPaths = [];
            // The action's own result is the freshest copy: reading the album back can
            // see the old version and leave the list stale.
            if (this.albums.some(a => a.id == album.id)) {
                this.albums = this.albums.map(a => a.id == album.id ? album : a);
            } else {
                this.albums = this.albums.concat([album])
                    .sort((a, b) => a.name.localeCompare(b.name));
            }
            this.$toast(this.translate("PHOTOS.ADDED.TO.ALBUM").replace("$NAME", album.name));
            if (this.currentAlbum != null && this.currentAlbum.id == album.id) {
                // Keep the open album current, or a share started from here would send
                // only the items it held when it was opened.
                this.currentAlbum = album;
                await this.loadAlbumItems(album);
            }
        },
        tagsChanged: async function() {
            await this.loadTags();
            if (this.currentAlbum != null)
                await this.loadAlbumItems(this.currentAlbum);
        },
        removeSelectedFromAlbum: async function() {
            if (this.currentAlbum == null || !this.albumIsOwn)
                return;
            let albumId = this.currentAlbum.id;
            let paths = this.selectedPaths.slice();
            if (paths.length == 0)
                return;
            this.showSpinner = true;
            let updated = null;
            let failures = [];
            try {
                for (let path of paths) {
                    try {
                        let album = await this.removeItemFromAlbum(albumId, path);
                        if (album != null)
                            updated = album;
                    } catch (e) {
                        failures.push(this.photoMessage(e));
                    }
                }
            } finally {
                if (updated != null) {
                    // Use what the removal returned rather than reading the album back:
                    // the open album then drops the file at once, with no stale window.
                    let index = this.albums.findIndex(a => a.id == updated.id);
                    if (index >= 0)
                        this.albums.splice(index, 1, updated);
                    this.currentAlbum = updated;
                    this.selectedPaths = this.selectedPaths.filter(path => updated.items.includes(path));
                    await this.loadAlbumItems(updated);
                } else {
                    await this.loadAlbums();
                    let fresh = this.albums.find(a => a.id == albumId);
                    if (fresh == null) {
                        this.closeAlbum();
                    } else {
                        this.currentAlbum = fresh;
                        this.selectedPaths = this.selectedPaths.filter(path => fresh.items.includes(path));
                        await this.loadAlbumItems(fresh);
                    }
                }
                this.showSpinner = false;
            }
            if (failures.length > 0)
                this.$toast.error(failures.join("; "), {timeout: false});
        },
        tagCount: function(tag) {
            return this.taggedItems.filter(item => (this.tags[item.path] || []).includes(tag)).length;
        },
        togglePhotoSelection: function(path, missing) {
            if (missing)
                return;
            let index = this.selectedPaths.indexOf(path);
            if (index >= 0)
                this.selectedPaths.splice(index, 1);
            else
                this.selectedPaths.push(path);
        },
        onItemClick: function(item) {
            if (item.missing || item.file == null)
                return;
            if (this.selectedPaths.length > 0) {
                this.togglePhotoSelection(item.path);
                return;
            }
            this.openItem(item);
        },
        openItem: function(item) {
            if (item.file == null)
                return;
            if (this.isPhotoImage(item.file)) {
                let images = this.visibleItems().filter(other => other.file != null && this.isPhotoImage(other.file));
                let index = images.findIndex(other => other.path == item.path);
                this.galleryFiles = images.map(other => other.file);
                this.galleryIndex = Math.max(0, index);
                this.showGallery = true;
                return;
            }
            let slash = item.path.lastIndexOf("/");
            let parent = slash < 0 ? "" : item.path.substring(0, slash);
            this.openFileOrDir("Drive", parent, {filename: item.name});
        },
        visibleItems: function() {
            if (this.tab == "tags")
                return this.tagFilteredItems;
            if (this.tab == "albums" || this.tab == "shared")
                return this.currentAlbumItems;
            return this.allPhotos;
        },
        // DriveGridCard asks each tile whether its menu is wanted; these tiles have none.
        menuStandsDown: function() {
            return true;
        },
        nothing: function() {},
    },
};
</script>

<style>
.photos-view main {
    max-width: 1280px;
}
.photos-tabs {
    display: flex;
    gap: 4px;
    border-bottom: 1px solid var(--border-color);
}
.photos-tabs button {
    padding: 10px 14px;
    color: var(--pg-muted);
    background: none;
    border: 0;
    border-bottom: 2px solid transparent;
    font: inherit;
    cursor: pointer;
}
.photos-tabs button.is-active {
    color: var(--color);
    border-bottom-color: var(--green-500);
}
.photos-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
}
.photos-toolbar__folders {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px;
}
.photos-selection {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    background-color: var(--pg-surface-2);
    border-radius: var(--radius-field);
}
.photos-selection__count {
    font-weight: var(--bold);
}
.photos-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
    margin: 0;
    padding: 0;
    list-style: none;
}
.photos-list__item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    width: 100%;
    padding: 12px;
    color: inherit;
    background: none;
    border: 0;
    border-bottom: 1px solid var(--border-color);
    font: inherit;
    text-align: left;
    cursor: pointer;
}
.photos-list__item:hover {
    background-color: var(--bg-2);
}
.photos-list__name {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.photos-list__name svg {
    width: 22px;
    height: 22px;
    flex: none;
    color: var(--pg-muted);
}
.photos-list__count {
    color: var(--pg-muted);
    font-size: var(--text-small);
    white-space: nowrap;
}
.photos-album-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
}
.photos-album-head h2 {
    margin: 0;
}
.photos-album-head__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-left: auto;
}
.photos-missing {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
}
.photos-tag-filter {
    margin-bottom: 4px;
}
.photos-tag-filter__item {
    border: 0;
    cursor: pointer;
}
.photos-tag-filter__item.pg-chip--on {
    color: var(--pg-on-ok);
    background-color: var(--pg-tint-ok);
}
@media (max-width: 1024px) {
    .photos-list__count {
        white-space: normal;
        text-align: right;
    }
    .photos-album-head__actions {
        margin-left: 0;
    }
}
</style>
