package io.github.electroheavenvn.zaloxposed.utilities;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BackgroundUtils {
    public static final String FOLDER_DISPLAY_PATH = "/sdcard/ZaloXposed/bg";
    public static final int REQUEST_CODE_PICK_IMAGE = 3721;
    public static final int REQUEST_CODE_PERMISSION = 3722;
    public static final int REQUEST_CODE_MANAGE_STORAGE = 3723;

    private static final int MAX_BITMAP_SIZE = 2560;
    private static final String[] LEGACY_PERMISSIONS = {
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    public enum FolderAccess {
        GRANTED,
        NEED_RUNTIME_PERMISSION,
        NEED_ALL_FILES_ACCESS,
        NOT_DECLARED
    }

    private BackgroundUtils() {}

    public static File getFolder() {
        return new File(
            Environment.getExternalStorageDirectory(),
            "ZaloXposed/bg"
        );
    }

    public static List<File> getFolderImages() {
        List<File> result = new ArrayList<>();
        File[] files = getFolder().listFiles();

        if (files == null) {
            return result;
        }

        for (File file : files) {
            if (file.isFile() && isImageFile(file)) {
                result.add(file);
            }
        }

        return result;
    }

    private static boolean isImageFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg")
            || name.endsWith(".jpeg")
            || name.endsWith(".png")
            || name.endsWith(".webp")
            || name.endsWith(".bmp");
    }

    public static File getPickedImageFile(Context context) {
        String dir = Utils.GetZaloXposedDir();
        File base = dir == null || dir.isEmpty()
            ? context.getFilesDir()
            : new File(dir);

        if (!base.exists() && !base.mkdirs() && !base.isDirectory()) {
            Logger.e("Cannot create directory: " + base);
        }

        return new File(base, "background_picker.jpg");
    }

    public static boolean hasPickedImage(Context context) {
        File file = getPickedImageFile(context);
        return file.isFile();
    }

    public static Activity findActivity(Context context) {
        Context ctx = context;

        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }

            Context next = ((ContextWrapper) ctx).getBaseContext();
            if (next == ctx) {
                break;
            }
            ctx = next;
        }

        return ctx instanceof Activity ? (Activity) ctx : null;
    }

    public static boolean isPermissionDeclared(
            Context context, String permission) {
        try {
            PackageInfo info;

            if (Build.VERSION.SDK_INT >= 33) {
                info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_PERMISSIONS
                    )
                );
            } else {
                info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    PackageManager.GET_PERMISSIONS
                );
            }

            String[] permissions = info.requestedPermissions;
            if (permissions == null) {
                return false;
            }

            for (String requested : permissions) {
                if (permission.equals(requested)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            Logger.e(e);
        }

        return false;
    }

    private static boolean hasAllFilesPermission(Context context) {
        return isPermissionDeclared(
            context, Manifest.permission.MANAGE_EXTERNAL_STORAGE
        );
    }

    private static boolean hasLegacyPermissionsDeclared(Context context) {
        return isPermissionDeclared(
                context, Manifest.permission.READ_EXTERNAL_STORAGE)
            && isPermissionDeclared(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    public static FolderAccess getFolderAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Apps still operating in legacy mode may access shared storage
            // without MANAGE_EXTERNAL_STORAGE.
            if (Environment.isExternalStorageLegacy()) {
                return checkLegacyAccess(context);
            }

            if (!hasAllFilesPermission(context)) {
                return FolderAccess.NOT_DECLARED;
            }

            return Environment.isExternalStorageManager()
                ? FolderAccess.GRANTED
                : FolderAccess.NEED_ALL_FILES_ACCESS;
        }

        return checkLegacyAccess(context);
    }

    private static FolderAccess checkLegacyAccess(Context context) {
        if (!hasLegacyPermissionsDeclared(context)) {
            return FolderAccess.NOT_DECLARED;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return FolderAccess.GRANTED;
        }

        for (String permission : LEGACY_PERMISSIONS) {
            if (context.checkSelfPermission(permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return FolderAccess.NEED_RUNTIME_PERMISSION;
            }
        }

        return FolderAccess.GRANTED;
    }

    public static String getRequestablePermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        for (String permission : LEGACY_PERMISSIONS) {
            if (isPermissionDeclared(context, permission)
                && context.checkSelfPermission(permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return permission;
            }
        }

        return null;
    }

    public static boolean ensureFolderExists(Context context) {
        FolderAccess access = getFolderAccess(context);

        if (access != FolderAccess.GRANTED) {
            Logger.i("Background folder access: " + access);
            return false;
        }

        try {
            File folder = getFolder();

            if (!folder.exists()
                && !folder.mkdirs()
                && !folder.isDirectory()) {
                Logger.e("Cannot create folder: " + folder);
                return false;
            }

            if (!folder.isDirectory()) {
                Logger.e("Path is not a directory: " + folder);
                return false;
            }

            // Test real write access instead of relying only on canWrite().
            File testFile = new File(folder, ".zalo_xposed_write_test");

            try (FileOutputStream output = new FileOutputStream(testFile)) {
                output.write(1);
                output.flush();
            } finally {
                if (testFile.exists() && !testFile.delete()) {
                    Logger.i("Could not delete test file: " + testFile);
                }
            }

            Logger.i("Background folder ready: " + folder);
            return true;
        } catch (Throwable e) {
            Logger.e(e);
            return false;
        }
    }

    public static boolean requestFolderAccess(Context context) {
        Activity activity = findActivity(context);

        if (activity == null) {
            Logger.e("Cannot request access without an Activity");
            return false;
        }

        FolderAccess access = getFolderAccess(context);

        if (access == FolderAccess.GRANTED) {
            return ensureFolderExists(context);
        }

        if (access == FolderAccess.NEED_RUNTIME_PERMISSION) {
            ArrayList<String> missing = new ArrayList<>();

            for (String permission : LEGACY_PERMISSIONS) {
                if (isPermissionDeclared(context, permission)
                    && context.checkSelfPermission(permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            }

            if (missing.isEmpty()) {
                return false;
            }

            activity.requestPermissions(
                missing.toArray(new String[0]),
                REQUEST_CODE_PERMISSION
            );
            return true;
        }

        if (access == FolderAccess.NEED_ALL_FILES_ACCESS
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())
                );

                activity.startActivityForResult(
                    intent, REQUEST_CODE_MANAGE_STORAGE
                );
                return true;
            } catch (Throwable e) {
                Logger.e(e);

                try {
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    );

                    activity.startActivityForResult(
                        intent, REQUEST_CODE_MANAGE_STORAGE
                    );
                    return true;
                } catch (Throwable ex) {
                    Logger.e(ex);
                }
            }
        }

        return false;
    }

    public static boolean onFolderAccessResult(Context context) {
        FolderAccess access = getFolderAccess(context);
        Logger.i("Storage access after returning: " + access);

        if (access != FolderAccess.GRANTED) {
            return false;
        }

        return ensureFolderExists(context);
    }

    public static boolean savePickedImage(Context context, Uri uri) {
        Bitmap bitmap = null;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bitmap = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(
                        context.getContentResolver(), uri
                    ),
                    (decoder, info, source) -> {
                        decoder.setAllocator(
                            ImageDecoder.ALLOCATOR_SOFTWARE
                        );

                        Size size = info.getSize();
                        int width = size.getWidth();
                        int height = size.getHeight();
                        int max = Math.max(width, height);

                        if (max > MAX_BITMAP_SIZE) {
                            float scale = MAX_BITMAP_SIZE / (float) max;

                            decoder.setTargetSize(
                                Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale))
                            );
                        }
                    }
                );
            } else {
                BitmapFactory.Options bounds =
                    new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;

                try (InputStream input =
                         context.getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        return false;
                    }

                    BitmapFactory.decodeStream(input, null, bounds);
                }

                int sampleSize = 1;
                int max = Math.max(bounds.outWidth, bounds.outHeight);

                while (max / (sampleSize * 2) >= MAX_BITMAP_SIZE) {
                    sampleSize *= 2;
                }

                BitmapFactory.Options options =
                    new BitmapFactory.Options();
                options.inSampleSize = sampleSize;

                try (InputStream input =
                         context.getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        return false;
                    }

                    bitmap = BitmapFactory.decodeStream(
                        input, null, options
                    );
                }
            }

            if (bitmap == null) {
                return false;
            }

            File target = getPickedImageFile(context);
            File parent = target.getParentFile();

            if (parent != null && !parent.exists()
                && !parent.mkdirs() && !parent.isDirectory()) {
                return false;
            }

            try (FileOutputStream output = new FileOutputStream(target)) {
                return bitmap.compress(
                    Bitmap.CompressFormat.JPEG, 95, output
                );
            }
        } catch (Throwable e) {
            Logger.e(e);
            return false;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }
}