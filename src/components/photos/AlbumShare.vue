<template>
<transition name="modal">
<div class="pg-dialog__mask" @click="close">
    <div class="pg-dialog" role="dialog" aria-modal="true" :aria-label="translate('PHOTOS.SHARE.ALBUM')" @click.stop>
        <header class="pg-dialog__head">
            <h2 class="pg-dialog__title">{{ translate("PHOTOS.SHARE.ALBUM") }}</h2>
            <DialogClose @close="close"/>
        </header>
        <div class="pg-dialog__body">
            <p class="pg-note">{{ translate("PHOTOS.SHARE.EXPLAIN").replace("$NAME", album.name) }}</p>
            <ul v-if="recipients.length > 0" class="photos-recipients">
                <li v-for="recipient in recipients" :key="recipient" class="photos-recipient">
                    <span>{{ recipient }}</span>
                    <button type="button" class="pg-btn pg-btn--danger" :disabled="busy" @click="askRemove(recipient)">
                        {{ translate("PHOTOS.REMOVE.RECIPIENT") }}
                    </button>
                </li>
            </ul>
            <div class="photos-share-form">
                <select class="pg-input" v-model="chosen" :disabled="busy || availableRecipients.length == 0">
                    <option value="">{{ translate("PHOTOS.SHARE.CHOOSE") }}</option>
                    <option v-for="user in availableRecipients" :key="user" :value="user">{{ user }}</option>
                </select>
                <button type="button" class="pg-btn pg-btn--primary" :disabled="busy || chosen.length == 0" @click="share">
                    {{ translate("PHOTOS.SHARE") }}
                </button>
            </div>
            <p v-if="availableRecipients.length == 0" class="pg-note">{{ translate("PHOTOS.SHARE.NO.RECIPIENTS") }}</p>
        </div>
    </div>
</div>
</transition>
<Confirm
    v-if="showConfirm"
    @hide-confirm="showConfirm = false"
    :confirm_message="translate('PHOTOS.REMOVE.CONFIRM').replace('$NAME', recipientToRemove)"
    :confirm_body="translate('PHOTOS.REMOVE.BODY')"
    :consumer_cancel_func="cancelRemove"
    :consumer_func="confirmRemove">
</Confirm>
</template>

<script>
const DialogClose = require("../dialog/DialogClose.vue");
const Confirm = require("../confirm/Confirm.vue");
const i18n = require("../../i18n/index.js");
const photos = require("../../mixins/photos/index.js");

module.exports = {
    components: { DialogClose, Confirm },
    mixins: [i18n, photos],
    props: ["album"],
    data: function() {
        return {
            shares: {version: 1, albums: {}},
            chosen: "",
            busy: false,
            showConfirm: false,
            recipientToRemove: "",
        };
    },
    computed: {
        ...Vuex.mapState(["context", "socialData"]),
        recipients: function() {
            let entries = this.shares.albums[this.album.id];
            return entries == null ? [] : Object.keys(entries).sort((a, b) => a.localeCompare(b));
        },
        availableRecipients: function() {
            let already = {};
            this.recipients.forEach(name => already[name] = true);
            return this.socialData.followers.concat(this.socialData.friends)
                .filter((name, index, all) => name != this.context.username && !already[name] && all.indexOf(name) == index)
                .sort((a, b) => a.localeCompare(b));
        },
    },
    mounted: async function() {
        await this.reload();
    },
    methods: {
        close: function() {
            this.$emit("close");
        },
        reload: async function() {
            try {
                this.shares = await this.loadPhotoShares();
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            }
        },
        share: async function() {
            if (this.busy || this.chosen.length == 0)
                return;
            this.busy = true;
            try {
                await this.sharePhotoAlbum(this.album, [this.chosen]);
                this.chosen = "";
                await this.reload();
                this.$toast(this.translate("PHOTOS.SHARE.COMPLETE"));
                this.$emit("changed");
            } catch (e) {
                this.$toast.error(this.photoMessage(e), {timeout: false});
            } finally {
                this.busy = false;
            }
        },
        askRemove: function(recipient) {
            this.recipientToRemove = recipient;
            this.showConfirm = true;
        },
        cancelRemove: function() {
            this.showConfirm = false;
            this.recipientToRemove = "";
        },
        confirmRemove: async function() {
            let recipient = this.recipientToRemove;
            this.showConfirm = false;
            this.recipientToRemove = "";
            this.busy = true;
            try {
                await this.removeRecipientFromAlbum(this.album, recipient);
                await this.reload();
                this.$toast(this.translate("PHOTOS.REMOVE.COMPLETE"));
                this.$emit("changed");
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
.photos-recipients {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin: 0 0 16px;
    padding: 0;
    list-style: none;
}
.photos-recipient {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
}
.photos-share-form {
    display: flex;
    gap: 8px;
    align-items: center;
}
.photos-share-form select {
    flex: 1 1 auto;
}
</style>
