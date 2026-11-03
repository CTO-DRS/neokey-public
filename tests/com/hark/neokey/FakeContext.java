package com.hark.neokey;

import java.io.File;

/** JVM fake for android.content.Context: real assets/ on disk + temp dirs for learned data. */
public class FakeContext extends android.content.Context {
    private final File assetsDir, filesDir, cacheDir;
    public FakeContext(File assetsDir, File filesDir, File cacheDir) {
        this.assetsDir = assetsDir; this.filesDir = filesDir; this.cacheDir = cacheDir;
    }
    /** Uses the app's real assets directory (CWD = project root under tests/run.sh). */
    public FakeContext(File filesDir) {
        this(new File("assets"), filesDir, new File(filesDir.getParentFile(), filesDir.getName() + "-cache"));
    }
    @Override public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(assetsDir); }
    @Override public File getFilesDir() { return filesDir; }
    @Override public File getCacheDir() { return cacheDir; }
}
