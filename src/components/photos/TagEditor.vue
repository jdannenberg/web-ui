<template>
<transition name="modal">
<div class="pg-dialog__mask" @click="close">
    <div class="pg-dialog" role="dialog" aria-modal="true" :aria-label="translate('PHOTOS.TAGS')" @click.stop>
        <header class="pg-dialog__head">
            <h2 class="pg-dialog__title">{{ translate("PHOTOS.TAGS") }}</h2>
            <DialogClose @close="close"/>
        </header>
        <div class="pg-dialog__body">
            <p class="pg-note">{{ translate("PHOTOS.TAGGING.ITEMS").replace("$COUNT", paths.length) }}</p>
            <div v-if="selectedTags.length > 0" class="pg-chips photos-tag-list">
                <span v-for="tag in selectedTags" :key="tag" class="pg-chip photos-tag">
                    {{ tag }}
                    <button type="button" class="photos-tag__remove" :aria-label="translate('PHOTOS.REMOVE.TAG')" :disabled="busy" @click="removeTag(tag)">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg>
                    </button>
                </span>
            </div>
            <form class="photos-new-tag" @submit.prevent="addTag">
                <input class="pg-input" v-model="newTag" :placeholder="translate('PHOTOS.TAG.NAME')" :disabled="busy">
                <button type="submit" class="pg-btn pg-btn--primary" :disabled="busy || newTag.trim().length == 0">
                    {{ translate("PHOTOS.ADD.TAG") }}
                </button>
            </form>
            <div v-if="suggestions.length > 0" class="photos-tag-suggestions">
                <span class="pg-note">{{ translate("PHOTOS.TAG.SUGGESTIONS") }}</span>
                <div class="pg-chips">
                    <button v-for="tag in suggestions" :key="tag" type="button" class="pg-chip photos-tag-suggestion" :disabled="busy" @click="addExistingTag(tag)">{{ tag }}</button>
                </div>
            </div>
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
            tags: {},
            newTag: "",
            busy: false,
        };
    },
    mounted: async function() {
        await this.reload();
    },
    computed: {
        ...Vuex.mapState(["context"]),
        selectedTags: function() {
            let found = [];
            let chosen = {};
            this.paths.map(p => this.normalizePhotoPath(p)).forEach(path => {
                (this.tags[path] || []).forEach(tag => {
                    if (!chosen[tag]) {
                        chosen[tag] = true;
                        found.push(tag);
                    }
                });
            });
            return found.sort((a, b) => a.localeCompare(b));
        },
        suggestions: function() {
            let applied = {};
            this.selectedTags.forEach(tag => applied[tag] = true);
            let all = [];
            Object.keys(this.tags).forEach(path => (this.tags[path] || []).forEach(tag => {
                if (!applied[tag])
                    all.push(tag);
            }));
            return Array.from(new Set(all)).sort((a, b) => a.localeCompare(b));
        },
    },
    methods: {
        close: function() {
            this.$emit("close");
        },
        reload: async function() {
            try {
                this.tags = await this.loadPhotoTags();
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            }
        },
        addTag: async function() {
            let tag = this.newTag.trim();
            if (this.busy || tag.length == 0)
                return;
            this.busy = true;
            try {
                this.tags = await this.addPhotoTags(this.paths, [tag]);
                this.newTag = "";
                this.$emit("done");
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            } finally {
                this.busy = false;
            }
        },
        addExistingTag: async function(tag) {
            if (this.busy)
                return;
            this.busy = true;
            try {
                this.tags = await this.addPhotoTags(this.paths, [tag]);
                this.$emit("done");
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            } finally {
                this.busy = false;
            }
        },
        removeTag: async function(tag) {
            if (this.busy)
                return;
            this.busy = true;
            try {
                this.tags = await this.removePhotoTags(this.paths, [tag]);
                this.$emit("done");
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
.photos-tag-list {
    margin-bottom: 16px;
}
.photos-tag {
    gap: 4px;
    padding-right: 5px;
}
.photos-tag__remove {
    display: inline-flex;
    padding: 2px;
    color: currentColor;
    background: none;
    border: 0;
    cursor: pointer;
}
.photos-new-tag {
    display: flex;
    gap: 8px;
    align-items: center;
}
.photos-new-tag .pg-input {
    flex: 1 1 auto;
}
.photos-tag-suggestions {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-top: 16px;
}
.photos-tag-suggestion {
    cursor: pointer;
    border: 0;
}
</style>
