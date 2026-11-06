import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** An album shared with another account: what they see, and what they lose.
 *
 *  The files do not move and are not copied. The owner grants read access to the album's
 *  metadata and to each file in it, and the recipient reads both in place. What matters is
 *  that this stays true as the album changes - a file added later arrives too - and that
 *  removing someone takes back only what the album gave.
 *
 *  Usage: java -cp ../server/Peergos.jar PhotosShareTest.java [engine] [url]
 */
public class PhotosShareTest {
    static final String PASSWORD = CalendarLiveShareTest.PASSWORD;
    static final String FOLDER = "SharedPhotos";
    static final String ALBUM = "Shared album";

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (AssertionError e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
    }

    public static void run(String[] args) throws Exception {
        String engine = args.length > 0 ? args[0] : "firefox";
        String given = args.length > 1 ? args[1] : null;
        boolean headless = ! "0".equals(System.getenv("HEADLESS"));
        Path serverDir = Paths.get("..", "server").toAbsolutePath().normalize();
        Path jar = serverDir.resolve("Peergos.jar");

        Server own = given == null ? Server.start(serverDir) : null;
        String url = own != null ? own.url() : given;
        long stamp = System.currentTimeMillis() % 100_000;
        String alice = "alice" + stamp, bob = "bob" + stamp;

        Fixtures.signUp(url, alice, PASSWORD);
        Fixtures.signUp(url, bob, PASSWORD);
        Path local = Temp.directory("peergos-photos-share-");
        Path first = png(local.resolve("first.png"), 0xC04030);
        Path later = png(local.resolve("later.png"), 0x3050C0);
        Path newOne = png(local.resolve("new.png"), 0x50A040);
        Path notes = local.resolve("notes.txt");
        Files.writeString(notes, "in an album, not a photo");
        Fixtures.commands(jar, url, alice, PASSWORD,
                "mkdir " + FOLDER,
                "cd " + FOLDER,
                "put " + first.toAbsolutePath(),
                "put " + later.toAbsolutePath(),
                "put " + notes.toAbsolutePath(),
                "put " + newOne.toAbsolutePath());
        String base = alice + "/" + FOLDER;
        String firstPath = base + "/first.png";
        String laterPath = base + "/later.png";
        String notesPath = base + "/notes.txt";
        String newPath = base + "/new.png";

        try (WebDriver d = Browsers.launch(Browsers.engine(engine),
                Temp.directory("peergos-photos-share-"), headless)) {
            CalendarLiveShareTest.befriend(d, url, alice, bob);
            System.out.println(alice + " and " + bob + " are friends");

            // --- the owner makes an album, of an image and a file that is not one ------------
            CalendarLiveShareTest.signIn(d, url, alice);
            PhotosAlbumTest.openPhotos(d);
            PhotosAlbumTest.chooseSource(d, "/" + base);
            PhotosAlbumTest.awaitPromise(d, "the album to be made",
                    "window.__photos.createPhotoAlbum(" + PhotosAlbumTest.quote(ALBUM)
                            + ", [" + PhotosAlbumTest.quote(firstPath) + ", "
                            + PhotosAlbumTest.quote(notesPath) + "])");
            PhotosAlbumTest.awaitPromise(d, "the album list", "window.__photos.loadAlbums()");
            d.waitForScript("the album to be there",
                    "window.__photos.albums.length === 1 && window.__photos.albums[0].name === "
                            + PhotosAlbumTest.quote(ALBUM), 60_000);

            PhotosAlbumTest.awaitPromise(d, "the album to be shared",
                    "(() => { let a = window.__photos.albums[0];"
                            + "  return window.__photos.sharePhotoAlbum(a, ["
                            + PhotosAlbumTest.quote(bob) + "]); })()");
            // The dialog reads back the bookkeeping, so it says who holds the album now.
            d.script("window.__photos.openAlbum(window.__photos.albums[0]); return 1;");
            d.waitForScript("the album open", "window.__photos.currentAlbum != null", 60_000);
            d.script("window.__photos.shareAlbum(); return 1;");
            d.waitForScript("the album's recipients", "!!document.querySelector('.photos-recipients')"
                    + " && document.querySelector('.photos-recipients').textContent.includes("
                    + PhotosAlbumTest.quote(bob) + ")", 60_000);
            d.script("window.__photos.sharingAlbum = null; return 1;");
            System.out.println("  ok   " + alice + " shared the album with " + bob);

            // --- the recipient finds it in Shared with me and reads both files in place -------
            CalendarLiveShareTest.signIn(d, url, bob);
            PhotosAlbumTest.openPhotos(d);
            d.script("window.__photos.switchTab('shared'); return 1;");
            d.waitForScript("the album in Shared with me",
                    "window.__photos.incomingAlbums.length === 1"
                            + " && window.__photos.incomingAlbums[0].owner === "
                            + PhotosAlbumTest.quote(alice)
                            + " && window.__photos.incomingAlbums[0].items.length === 2", 180_000);
            d.script("const b = [...document.querySelectorAll('.photos-list__item')]"
                    + "  .find(x => x.textContent.includes(arguments[0]));"
                    + "if (!b) throw new Error('no shared album row'); b.click(); return 1;", ALBUM);
            d.waitForScript("the shared album's files",
                    "window.__photos.currentAlbumItems.length === 2", 120_000);
            if (! Boolean.TRUE.equals(d.script("return window.__photos.albumIsOwn === false")))
                throw new AssertionError("An album someone else owns must not be offered as one's own");
            if (Boolean.TRUE.equals(d.script(
                    "return !!document.querySelector('.photos-album-head__actions')")))
                throw new AssertionError("A recipient was offered the owner's album actions");
            List<String> shown = PhotosAlbumTest.names(d, ".grid-card .card__name");
            if (! new HashSet<>(shown).equals(new HashSet<>(List.of("first.png", "notes.txt"))))
                throw new AssertionError("The shared album should hold the image and the text file, but holds " + shown);
            System.out.println("  ok   " + bob + " sees the album, its image and its text file, read only");

            // --- a file added after the share reaches the recipient --------------------------
            CalendarLiveShareTest.signIn(d, url, alice);
            PhotosAlbumTest.openPhotos(d);
            PhotosAlbumTest.awaitPromise(d, "the album to load", "window.__photos.loadAlbums()");
            PhotosAlbumTest.awaitPromise(d, "the added file to be put in the album",
                    "window.__photos.addItemsToAlbum(window.__photos.albums[0].id, ["
                            + PhotosAlbumTest.quote(newPath) + "])");

            CalendarLiveShareTest.signIn(d, url, bob);
            PhotosAlbumTest.openPhotos(d);
            d.script("window.__photos.switchTab('shared'); return 1;");
            d.waitForScript("the album after the addition",
                    "window.__photos.incomingAlbums.length === 1"
                            + " && window.__photos.incomingAlbums[0].items.length === 3", 180_000);
            d.script("window.__photos.openIncomingAlbum(window.__photos.incomingAlbums[0]); return 1;");
            d.waitForScript("the new file in the shared album",
                    "window.__photos.currentAlbumItems.length === 3", 120_000);
            System.out.println("  ok   a file added after the share reaches " + bob);

            // --- taking one file out takes it away from the recipient ------------------------
            CalendarLiveShareTest.signIn(d, url, alice);
            PhotosAlbumTest.openPhotos(d);
            PhotosAlbumTest.awaitPromise(d, "the album to load", "window.__photos.loadAlbums()");
            PhotosAlbumTest.awaitPromise(d, "the file to be removed from the album",
                    "window.__photos.removeItemFromAlbum(window.__photos.albums[0].id, "
                            + PhotosAlbumTest.quote(notesPath) + ")");

            CalendarLiveShareTest.signIn(d, url, bob);
            PhotosAlbumTest.openPhotos(d);
            d.script("window.__photos.switchTab('shared'); return 1;");
            d.waitForScript("the album after the removal",
                    "window.__photos.incomingAlbums.length === 1"
                            + " && window.__photos.incomingAlbums[0].items.length === 2", 180_000);
            d.script("window.__photos.openIncomingAlbum(window.__photos.incomingAlbums[0]); return 1;");
            d.waitForScript("the remaining files", "window.__photos.currentAlbumItems.length === 2", 120_000);
            d.script("window.__removed = null;"
                    + "window.__photos.resolvePhotoItems([" + PhotosAlbumTest.quote(notesPath) + "])"
                    + "  .then(r => { window.__removed = r[0]; }); return 1;");
            d.waitForScript("the removed file to be resolved", "!!window.__removed", 120_000);
            if (! Boolean.TRUE.equals(d.script("return window.__removed.missing === true")))
                throw new AssertionError("A file taken out of the album should no longer be readable, but is");
            System.out.println("  ok   taking the text file out of the album took " + bob + "'s access with it");

            // --- removing the recipient takes back the rest, from the dialog -----------------
            CalendarLiveShareTest.signIn(d, url, alice);
            PhotosAlbumTest.openPhotos(d);
            d.script("window.__photos.switchTab('albums'); return 1;");
            PhotosAlbumTest.awaitPromise(d, "the album list", "window.__photos.loadAlbums()");
            d.script("window.__photos.openAlbum(window.__photos.albums[0]); return 1;");
            d.waitForScript("the album open", "window.__photos.currentAlbumItems.length === 2", 60_000);
            d.script("window.__photos.shareAlbum(); return 1;");
            d.waitForScript("the album's recipients", "!!document.querySelector('.photos-recipient')", 60_000);
            d.script("document.querySelector('.photos-recipient button').click(); return 1;");
            d.waitForScript("the removal question", "!!document.getElementById('confirm-header-id')", 60_000);
            d.script("const b = [...document.querySelectorAll('.pg-dialog button')]"
                    + "  .find(x => x.textContent.trim() === 'Yes');"
                    + "if (!b) throw new Error('no Yes in the removal question'); b.click(); return 1;");
            d.waitForScript("the album to have no recipients", "window.__photos.sharingAlbum != null"
                    + " && window.__photos.sharingAlbum.id != null"
                    + " && document.querySelector('.photos-recipients') == null", 180_000);
            d.script("window.__photos.sharingAlbum = null; return 1;");
            System.out.println("  ok   removing " + bob + " from the album was asked and confirmed");

            // --- and the recipient has neither the album nor its files ----------------------
            CalendarLiveShareTest.signIn(d, url, bob);
            PhotosAlbumTest.openPhotos(d);
            d.script("window.__photos.switchTab('shared'); return 1;");
            d.waitForScript("no shared albums left", "window.__photos.incomingAlbums.length === 0", 180_000);
            d.script("window.__photos.resolvePhotoItems([" + PhotosAlbumTest.quote(firstPath)
                    + ", " + PhotosAlbumTest.quote(newPath) + "])"
                    + "  .then(r => { window.__gone = r; }); return 1;");
            d.waitForScript("the files to be resolved", "!!window.__gone", 120_000);
            if (! Boolean.TRUE.equals(d.script(
                    "return window.__gone.every(x => x.missing === true);")))
                throw new AssertionError("Removing the recipient should have taken back the album's files, but some are still readable");
            System.out.println("  ok   " + bob + " no longer sees the album or reads its files");

            System.out.println("PASS");
        } finally {
            if (own != null)
                own.close();
        }
    }

    static Path png(Path target, int rgb) throws Exception {
        BufferedImage image = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                image.setRGB(x, y, rgb);
        ImageIO.write(image, "png", target.toFile());
        return target;
    }
}
