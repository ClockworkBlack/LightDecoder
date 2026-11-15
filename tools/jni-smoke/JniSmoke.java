import com.lightdecoder.heif.NativeDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

// Drives the real JNI entry points over a real iPhone sticker and checks the
// results against the native command-line probe's ground truth.
public class JniSmoke {
    public static void main(String[] args) throws Exception {
        System.out.println(NativeDecoder.nativeVersions());
        int failures = 0;
        for (String path : args) {
            failures += run(path);
        }
        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("ALL JNI CHECKS PASSED");
    }

    static int run(String path) throws Exception {
        byte[] data = Files.readAllBytes(Path.of(path));
        long h = NativeDecoder.nativeOpen(data);
        if (h == 0) {
            System.out.println("FAIL open " + path);
            return 1;
        }
        try {
            String desc = NativeDecoder.nativeDescribe(h);
            boolean seq = NativeDecoder.nativeHasSequence(h);
            boolean prepared = seq
                    ? NativeDecoder.nativePrepareNextFrame(h) == 1
                    : NativeDecoder.nativePrepareStill(h, 0);
            if (!prepared) {
                System.out.println("FAIL decode " + path + ": " + NativeDecoder.nativeLastError(h));
                return 1;
            }
            int w = NativeDecoder.nativePreparedWidth(h);
            int hgt = NativeDecoder.nativePreparedHeight(h);
            boolean alpha = NativeDecoder.nativePreparedHasAlpha(h);
            byte[] rgba = NativeDecoder.nativeCopyRGBA(h);

            int expected = w * hgt * 4;
            boolean sizeOk = rgba != null && rgba.length == expected;
            // A transparent sticker must actually contain some transparent pixels.
            boolean alphaOk = !alpha || hasTransparentPixel(rgba);
            String md5 = rgba == null ? "none" : md5(rgba);

            String name = Path.of(path).getFileName().toString();
            System.out.printf("%-16s %dx%d alpha=%b bytes=%d/%d %s md5=%s%n",
                    name, w, hgt, alpha, rgba == null ? 0 : rgba.length, expected,
                    (sizeOk && alphaOk) ? "OK" : "BAD", md5);
            return (sizeOk && alphaOk) ? 0 : 1;
        } finally {
            NativeDecoder.nativeClose(h);
        }
    }

    static boolean hasTransparentPixel(byte[] rgba) {
        if (rgba == null) return false;
        for (int i = 3; i < rgba.length; i += 4) {
            if ((rgba[i] & 0xFF) < 255) return true;
        }
        return false;
    }

    static String md5(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
