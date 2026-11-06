import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Photos: the folders someone chooses, the tags on their files, and albums.
 *
 *  Nothing here lives in the files themselves. The source folders decide what All photos
 *  shows, a tag is kept beside the file rather than in it, and an album is a list of paths -
 *  so the same file can be in two albums, and taking it out of one leaves it where it is.
 *
 *  Usage: java -cp ../server/Peergos.jar PhotosAlbumTest.java [engine] [url]
 */
public class PhotosAlbumTest {
    static final String PASSWORD = "photos-album-password";
    static final String FOLDER = "Photos";
    static final String ALBUM = "Trip";
    static final String RENAMED = "Summer trip";
    static final String TAG = "holiday";

    static Fixtures.Account me;

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
        me = Fixtures.account(url);

        // One image per directory level, a non-image beside them, and a file outside the
        // chosen folders: All photos may take only what the source folders hold.
        Path local = Temp.directory("peergos-photos-");
        Path one = png(local.resolve("one.png"), 0xC04030);
        Path two = png(local.resolve("two.png"), 0x3050C0);
        Path three = png(local.resolve("three.png"), 0x50A040);
        Path notes = local.resolve("notes.txt");
        Files.writeString(notes, "not a photo");
        Path elsewhere = png(local.resolve("elsewhere.png"), 0x808080);
        Fixtures.commands(jar, url, me.name(), me.password(),
                "mkdir " + FOLDER,
                "mkdir Photos/Sub",
                "mkdir Other",
                "cd Photos",
                "put " + one.toAbsolutePath(),
                "put " + two.toAbsolutePath(),
                "put " + notes.toAbsolutePath(),
                "cd Sub",
                "put " + three.toAbsolutePath(),
                "cd ../../Other",
                "put " + elsewhere.toAbsolutePath());
        String base = me.name() + "/" + FOLDER;
        String onePath = base + "/one.png";
        String twoPath = base + "/two.png";
        String threePath = base + "/Sub/three.png";
        String notesPath = base + "/notes.txt";

        try (WebDriver d = Browsers.launch(Browsers.engine(engine),
                Temp.directory("peergos-photos-album-"), headless)) {
            d.navigate(url + "/");
            d.waitForScript("login form", "document.querySelector('input[name=username]')", 60_000);
            Page.login(d, me.name(), me.password());

            openPhotos(d);
            d.waitForScript("nothing chosen yet",
                    "window.__photos.sources.length === 0 && window.__photos.allPhotos.length === 0", 60_000);
            d.waitForScript("the empty state", "!!document.querySelector('.photos-empty, .pg-empty')", 60_000);
            System.out.println("  ok   Photos starts with no folders and says where to choose them");

            chooseSource(d, "/" + base);
            d.waitForScript("the images in that folder and below",
                    "window.__photos.allPhotos.length === 3", 180_000);
            List<String> shown = names(d, ".grid-card .card__name");
            if (! new HashSet<>(shown).equals(new HashSet<>(List.of("one.png", "two.png", "three.png"))))
                throw new AssertionError("All photos should hold every image in the chosen folders, "
                        + "and nothing else, but shows " + shown);
            System.out.println("  ok   All photos reads the chosen folders recursively and skips "
                    + "the text file and the folder left out");

            // --- tags, from the Drive, on an image and on something that is not one ----------
            Page.gotoDrive(d);
            Page.openPath(d, "/" + base, FOLDER);
            Page.waitForInDrive(d, "one.png", 120_000);
            tagFile(d, "one.png", TAG);
            tagFile(d, "notes.txt", "work");
            System.out.println("  ok   the Drive's menu tags an image and a text file alike");

            // --- an album made from the same menu -------------------------------------------
            Page.select(d, "one.png");
            Object offered = d.scriptQuiet("return window.__drive.canTagSelection");
            if (! Boolean.TRUE.equals(offered))
                throw new AssertionError("A selected file should offer Add to album and Tags");
            d.script("window.__drive.showAddToAlbum(); return 1;");
            d.waitForScript("the album picker", "window.__drive.showAlbumPicker && "
                    + Page.VUE + ".find('createAndAdd') != null", 60_000);
            awaitPromise(d, "the album to be made", "(() => {"
                    + "  let c = " + Page.VUE + ".find('createAndAdd');"
                    + "  c.newName = " + quote(ALBUM) + ";"
                    + "  return c.createAndAdd();"
                    + "})()");
            d.waitForScript("the picker to close", "!window.__drive.showAlbumPicker", 60_000);
            System.out.println("  ok   Add to album makes one with the picked file in it");

            // --- the album in Photos, with two more files picked there ----------------------
            openPhotos(d);
            awaitPromise(d, "the albums to load", "window.__photos.loadAlbums()");
            d.script("window.__photos.switchTab('albums'); return 1;");
            d.waitForScript("the album list to hold it", "window.__photos.albums.length === 1"
                    + " && window.__photos.albums[0].name === " + quote(ALBUM), 60_000);
            d.script("const button = [...document.querySelectorAll('.photos-list__item')]"
                    + "  .find(b => b.textContent.includes(arguments[0]));"
                    + "if (!button) throw new Error('no album row'); button.click(); return 1;", ALBUM);
            d.waitForScript("the album to open with its one item",
                    "window.__photos.currentAlbum != null && window.__photos.currentAlbumItems.length === 1", 60_000);
            System.out.println("  ok   the album opens with the file the Drive put in it");

            d.script("window.__photos.switchTab('all'); return 1;");
            d.waitForScript("All photos again", "window.__photos.allPhotos.length === 3", 60_000);
            d.script("window.__photos.togglePhotoSelection(arguments[0]);"
                    + "window.__photos.togglePhotoSelection(arguments[1]); return 1;", twoPath, threePath);
            d.waitForScript("two files picked", "window.__photos.selectedPaths.length === 2", 60_000);
            d.script("const b = [...document.querySelectorAll('.photos-selection button')]"
                    + "  .find(x => x.textContent.trim() === 'Add to album...');"
                    + "if (!b) throw new Error('no Add to album in the selection bar'); b.click(); return 1;");
            d.waitForScript("the album picker from Photos", "window.__photos.showAlbumPicker && "
                    + Page.VUE + ".find('addTo') != null", 60_000);
            awaitPromise(d, "the files to be added to the album", "(() => {"
                    + "  let c = " + Page.VUE + ".find('addTo');"
                    + "  return c.addTo(c.albums[0]);"
                    + "})()");
            d.waitForScript("the album to hold three files",
                    "window.__photos.albums[0].items.length === 3", 180_000);
            System.out.println("  ok   files picked in All photos join the album too");

            // --- tags are shown together, with the tag as a filter -------------------------
            d.script("window.__photos.switchTab('tags'); return 1;");
            d.waitForScript("both tagged files", "window.__photos.taggedItems.length === 2", 120_000);
            List<String> tagged = names(d, ".grid-card .card__name");
            if (! new HashSet<>(tagged).equals(new HashSet<>(List.of("one.png", "notes.txt"))))
                throw new AssertionError("The tag view should hold the image and the text file, but holds " + tagged);
            d.waitForScript("the tag's filter chip", "([...document.querySelectorAll('.photos-tag-filter__item')]"
                    + "  .some(e => e.textContent.includes(" + quote(TAG) + ")))", 60_000);
            System.out.println("  ok   the tag view holds the tagged image and text file, and filters by tag");

            // --- a file deleted from the Drive is shown as missing, its album entry kept ----
            Page.gotoDrive(d);
            Page.openPath(d, "/" + base + "/Sub", "Sub");
            Page.waitForInDrive(d, "three.png", 120_000);
            Page.select(d, "three.png");
            d.script("window.__drive.deleteFilesMultiSelect(); return 1;");
            d.waitForScript("the delete question", "window.__drive.showPrompt", 60_000);
            d.script("window.__drive.prompt_consumer_func(''); return 1;");
            d.waitUntil("three.png to be deleted",
                    () -> ! Page.driveListing(d).contains("three.png"), 120_000);

            openPhotos(d);
            d.script("window.__photos.switchTab('albums'); return 1;");
            awaitPromise(d, "the album list to load", "window.__photos.loadAlbums()");
            d.script("window.__photos.openAlbum(window.__photos.albums[0]); return 1;");
            d.waitForScript("the missing file still listed",
                    "window.__photos.currentAlbumItems.length === 3"
                    + " && window.__photos.currentAlbum.items.length === 3"
                    + " && window.__photos.currentAlbumItems.filter(i => i.missing).length === 1", 120_000);
            d.waitForScript("the missing file's card", "(() => {"
                    + "  let card = [...document.querySelectorAll('.grid-card')].find(c =>"
                    + "    c.querySelector('.card__name').textContent === 'three.png');"
                    + "  return card != null && card.classList.contains('is-missing')"
                    + "    && card.querySelector('.card__missing').textContent.trim() === 'Missing';"
                    + "})()", 60_000);
            System.out.println("  ok   a deleted file stays in the album and is shown as missing");

            // --- rename, then delete: the files stay where they are --------------------------
            d.script("window.__photos.switchTab('albums'); return 1;");
            d.waitForScript("the album after the additions", "window.__photos.albums[0].items.length === 3", 60_000);
            d.script("window.__photos.openAlbum(window.__photos.albums[0]); return 1;");
            d.waitForScript("the album open", "window.__photos.currentAlbumItems.length === 3", 60_000);

            // --- removing one updates the open album immediately ------------------------------
            d.script("window.__photos.togglePhotoSelection(" + quote(twoPath) + "); return 1;");
            awaitPromise(d, "the file to leave the album", "window.__photos.removeSelectedFromAlbum()");
            d.waitForScript("the open album to drop it without reopening",
                    "window.__photos.currentAlbumItems.length === 2"
                    + " && window.__photos.currentAlbum.items.length === 2"
                    + " && window.__photos.albums[0].items.length === 2", 60_000);
            System.out.println("  ok   removing a file updates the open album at once");

            d.script("window.__photos.renameAlbum(); return 1;");
            d.waitForScript("the rename prompt", "!!document.getElementById('prompt-input')", 60_000);
            setInput(d, "#prompt-input", RENAMED);
            d.script("document.getElementById('prompt-button-id').click(); return 1;");
            d.waitForScript("the renamed album",
                    "window.__photos.currentAlbum != null && window.__photos.currentAlbum.name === "
                            + quote(RENAMED), 120_000);
            d.script("window.__photos.askDeleteAlbum(); return 1;");
            d.waitForScript("the delete question", "!!document.getElementById('confirm-header-id')", 60_000);
            d.script("const b = [...document.querySelectorAll('.pg-dialog button')]"
                    + "  .find(x => x.textContent.trim() === 'Yes');"
                    + "if (!b) throw new Error('no Yes in the delete question'); b.click(); return 1;");
            d.waitForScript("the album to be gone",
                    "window.__photos.currentAlbum == null && window.__photos.albums.length === 0", 180_000);
            d.script("window.__photos.switchTab('all'); return 1;");
            d.waitForScript("the files still listed after the album went",
                    "['one.png', 'two.png'].every(n => window.__photos.allPhotos.some(p => p.name === n))", 60_000);
            System.out.println("  ok   renaming and deleting the album leaves every file in place");

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

    /** Opens Photos and hands back a handle on it as window.__photos. */
    static void openPhotos(WebDriver d) {
        Page.gotoView(d, "Photos", "loadAllPhotos", "__photos");
        d.waitForScript("Photos to settle", "window.__photos != null && !window.__photos.loadingPhotos", 120_000);
    }

    /** Picks one folder in the picker Photos opens, the way a person does. */
    static void chooseSource(WebDriver d, String path) {
        d.script("window.__photos.showFolderPicker = true; return 1;");
        d.waitForScript("the folder picker", "!!document.querySelector('.fp-picker .fp-row__input')", 60_000);
        d.waitForScript("the " + path + " row",
                "!!document.querySelector('.fp-row__input[value=\"" + path + "\"]')", 120_000);
        d.script("document.querySelector('.fp-row__input[value=\"' + arguments[0] + '\"]').click();", path);
        d.script("[...document.querySelectorAll('.fp-picker button')]"
                + ".find(b => b.textContent.trim() === 'Select').click();");
        d.waitForScript("the source folder to be stored",
                "window.__photos.sources.length === 1 && window.__photos.sources[0] === "
                        + quote(path.substring(1)), 120_000);
    }

    /** Tags one file through the Drive's own menu, and closes the dialog it opens. */
    static void tagFile(WebDriver d, String name, String tag) {
        Page.select(d, name);
        d.script("window.__drive.showTagEditorForSelection(); return 1;");
        d.waitForScript("the tag editor", "window.__drive.showTagEditor && "
                + Page.VUE + ".find('addTag') != null", 60_000);
        awaitPromise(d, "the tag to be saved", "(() => {"
                + "  let c = " + Page.VUE + ".find('addTag');"
                + "  c.newTag = " + quote(tag) + ";"
                + "  return c.addTag();"
                + "})()");
        String path = me.name() + "/" + FOLDER + "/" + name;
        d.waitForScript("the tag to be on " + name, "(() => {"
                + "  let c = " + Page.VUE + ".find('addTag');"
                + "  return c != null && (c.tags[" + quote(path) + "] || []).includes(" + quote(tag) + ");"
                + "})()", 60_000);
        d.script("let c = " + Page.VUE + ".find('addTag'); if (c) c.close(); return 1;");
        d.waitForScript("the tag editor to close", "!window.__drive.showTagEditor", 30_000);
    }

    /** Runs an expression that yields a promise, and waits for it to finish. */
    static void awaitPromise(WebDriver d, String what, String expression) {
        d.script("window.__done = null; window.__failed = null;"
                + "try { Promise.resolve(" + expression + ")"
                + "  .then(v => { window.__done = true; })"
                + "  .catch(e => { window.__failed = '' + e; }); }"
                + "catch (e) { window.__failed = '' + e; } return 1;");
        d.waitUntil(what, () -> {
            Object failed = d.script("return window.__failed");
            if (failed != null && ! String.valueOf(failed).isEmpty())
                throw new AssertionError(what + " failed: " + failed);
            return Boolean.TRUE.equals(d.script("return window.__done === true"));
        }, 180_000);
    }

    static void setInput(WebDriver d, String selector, String value) {
        d.script("const el = document.querySelector(arguments[0]);"
                + "const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;"
                + "set.call(el, arguments[1]);"
                + "el.dispatchEvent(new Event('input', {bubbles: true}));", selector, value);
    }

    static List<String> names(WebDriver d, String selector) {
        Object found = d.script("return [...document.querySelectorAll(arguments[0])]"
                + ".map(e => e.textContent.trim());", selector);
        List<String> out = new ArrayList<>();
        if (found instanceof List)
            for (Object o : (List<?>) found)
                out.add(String.valueOf(o));
        return out;
    }

    static String quote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
