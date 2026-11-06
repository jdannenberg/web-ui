<template>
<transition name="modal">
<div class="pg-dialog__mask" @click="close">
    <div class="pg-dialog" role="dialog" aria-modal="true" :aria-label="translate('PHOTOS.ADD.TO.ALBUM')" @click.stop>
        <header class="pg-dialog__head">
            <h2 class="pg-dialog__title">{{ translate("PHOTOS.ADD.TO.ALBUM") }}</h2>
            <DialogClose @close="close"/>
        </header>
        <div class="pg-dialog__body">
            <p class="pg-note">{{ translate("PHOTOS.ADDING.ITEMS").replace("$COUNT", paths.length) }}</p>
            <ul v-if="albums.length > 0" class="photos-album-picker">
                <li v-for="album in albums" :key="album.id">
                    <button type="button" class="pg-btn photos-album-picker__item" :disabled="busy" @click="addTo(album)">
                        <span class="photos-album-picker__name">{{ album.name }}</span>
                        <span class="photos-album-picker__count">{{ album.items.length }}</span>
                    </button>
                </li>
            </ul>
            <p v-else class="pg-note">{{ translate("PHOTOS.NO.ALBUMS") }}</p>
            <form class="photos-new-album" @submit.prevent="createAndAdd">
                <input class="pg-input" v-model="newName" :placeholder="translate('PHOTOS.ALBUM.NAME')" :disabled="busy">
                <button type="submit" class="pg-btn pg-btn--primary" :disabled="busy || newName.trim().length == 0">
                    {{ translate("PHOTOS.CREATE.ALBUM") }}
                </button>
            </form>
        </div>
    </div>
</div>
</transition>
</template>

<script>
const DialogClose = require("../dialog/DialogClose.vue");
const i18n = require("../../i18n/index.js");
const photos = require("../../mixins/photos/index.js");

module.exports = {
    components: { DialogClose },
    mixins: [i18n, photos],
    props: ["paths"],
    data: function() {
        return {
            albums: [],
            newName: "",
            busy: false,
        };
    },
    computed: {
        ...Vuex.mapState(["context"]),
    },
    mounted: async function() {
        try {
            this.albums = await this.loadPhotoAlbums();
        } catch (e) {
            this.$toast.error(this.photoMessage(e), {timeout: false});
        }
    },
    methods: {
        close: function() {
            this.$emit("close");
        },
        addTo: async function(album) {
            if (this.busy)
                return;
            this.busy = true;
            try {
                let updated = await this.addItemsToAlbum(album.id, this.paths);
                this.$emit("done", updated);
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            } finally {
                this.busy = false;
            }
        },
        createAndAdd: async function() {
            if (this.busy || this.newName.trim().length == 0)
                return;
            this.busy = true;
            try {
                let album = await this.createPhotoAlbum(this.newName, this.paths);
                this.$emit("done", album);
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            } finally {
                this.busy = false;
            }
        },
    },
};
</script>

<style>
.photos-album-picker {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin: 0 0 16px;
    padding: 0;
    list-style: none;
    max-height: 40vh;
    overflow-y: auto;
}
.photos-album-picker__item {
    display: flex;
    justify-content: space-between;
    width: 100%;
}
.photos-album-picker__count {
    color: var(--pg-muted);
}
.photos-new-album {
    display: flex;
    gap: 8px;
    align-items: center;
}
.photos-new-album .pg-input {
    flex: 1 1 auto;
}
</style>
