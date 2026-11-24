import io.causvane.gui.AppIcon;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes the application icon files used for native packaging. Run from the project root after a build:
 *
 *   java -cp target/CausVane.jar packaging/IconExport.java packaging
 *
 * Produces CausVane.png (Linux), CausVane.ico (Windows) and CausVane.iconset/ (turned into
 * CausVane.icns on macOS with: iconutil -c icns packaging/CausVane.iconset).
 */
public class IconExport {

    public static void main(String[] args) throws IOException {
        File dir = new File(args.length > 0 ? args[0] : "packaging");
        ImageIO.write(AppIcon.render(512, 0), "png", new File(dir, "CausVane.png"));

        File iconset = new File(dir, "CausVane.iconset");
        iconset.mkdirs();
        for (int size : new int[] {16, 32, 128, 256, 512}) {
            ImageIO.write(AppIcon.render(size, 0.09), "png", new File(iconset, "icon_" + size + "x" + size + ".png"));
            ImageIO.write(AppIcon.render(size * 2, 0.09), "png", new File(iconset, "icon_" + size + "x" + size + "@2x.png"));
        }

        writeIco(new File(dir, "CausVane.ico"), 16, 24, 32, 48, 64, 128, 256);
        System.out.println("Icons written to " + dir.getAbsolutePath());
    }

    /** ICO container holding one PNG-encoded image per size. */
    private static void writeIco(File file, int... sizes) throws IOException {
        byte[][] images = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(AppIcon.render(sizes[i], 0), "png", png);
            images[i] = png.toByteArray();
        }
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = header.capacity();
        for (int i = 0; i < sizes.length; i++) {
            header.put((byte) (sizes[i] >= 256 ? 0 : sizes[i])).put((byte) (sizes[i] >= 256 ? 0 : sizes[i]));
            header.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            header.putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.write(header.array());
            for (byte[] image : images) {
                out.write(image);
            }
        }
    }
}
