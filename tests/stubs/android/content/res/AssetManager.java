package android.content.res;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;

/** Minimal JVM test stub backed by a real directory of asset files. */
public class AssetManager {
    private final File dir;
    public AssetManager(File dir) { this.dir = dir; }
    public InputStream open(String name) throws IOException {
        File f = new File(dir, name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return new FileInputStream(f);
    }
}
