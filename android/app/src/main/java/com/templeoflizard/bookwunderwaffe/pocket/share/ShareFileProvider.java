package com.templeoflizard.bookwunderwaffe.pocket.share;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Minimal read-only content provider for sharing finished audio and portable exports.
 * Keeping this in the app avoids pulling a support library into the offline build.
 */
public final class ShareFileProvider extends ContentProvider {
    private static final String ROOT_FILES = "files";
    private static final String ROOT_CACHE = "cache";

    public static Uri uriForFile(Context context, File file) {
        try {
            File canonical = file.getCanonicalFile();
            File filesRoot = context.getFilesDir().getCanonicalFile();
            File cacheRoot = context.getCacheDir().getCanonicalFile();
            String root;
            String relative;
            if (isInside(filesRoot, canonical)) {
                root = ROOT_FILES;
                relative = relativize(filesRoot, canonical);
            } else if (isInside(cacheRoot, canonical)) {
                root = ROOT_CACHE;
                relative = relativize(cacheRoot, canonical);
            } else {
                throw new IllegalArgumentException("File is outside shareable app storage");
            }
            Uri.Builder builder = new Uri.Builder()
                    .scheme("content")
                    .authority(context.getPackageName() + ".files")
                    .appendPath(root);
            for (String segment : relative.split("/")) {
                if (!segment.isEmpty()) {
                    builder.appendPath(segment);
                }
            }
            return builder.build();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot resolve shared file", exception);
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String name = resolve(uri).getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".bwlbook") || name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String[] requested = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(requested, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : requested) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Share provider is read-only");
        }
        File file = resolve(uri);
        if (!file.isFile()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    private File resolve(Uri uri) {
        Context context = getContext();
        if (context == null || uri == null
                || !context.getPackageName().concat(".files").equals(uri.getAuthority())) {
            throw new SecurityException("Unknown share authority");
        }
        java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() < 2) {
            throw new IllegalArgumentException("Malformed share URI");
        }
        File root;
        if (ROOT_FILES.equals(segments.get(0))) {
            root = context.getFilesDir();
        } else if (ROOT_CACHE.equals(segments.get(0))) {
            root = context.getCacheDir();
        } else {
            throw new SecurityException("Unknown share root");
        }
        File target = root;
        for (int index = 1; index < segments.size(); index++) {
            String segment = segments.get(index);
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new SecurityException("Invalid share path");
            }
            target = new File(target, segment);
        }
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonicalTarget = target.getCanonicalFile();
            if (!isInside(canonicalRoot, canonicalTarget)) {
                throw new SecurityException("Share path escapes app storage");
            }
            return canonicalTarget;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot resolve share URI", exception);
        }
    }

    private static boolean isInside(File root, File target) {
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        return targetPath.equals(rootPath)
                || targetPath.startsWith(rootPath + File.separator);
    }

    private static String relativize(File root, File target) {
        String relative = target.getPath().substring(root.getPath().length());
        while (relative.startsWith(File.separator)) {
            relative = relative.substring(1);
        }
        return relative.replace(File.separatorChar, '/');
    }
}
