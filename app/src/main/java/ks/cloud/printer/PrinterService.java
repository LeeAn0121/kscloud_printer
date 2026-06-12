package ks.cloud.printer;

import android.app.IntentService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Log;
import java.io.File;
import java.io.InputStream;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PrinterService extends IntentService {

    private static final String TAG = "KS_PRINTER_SERVICE";
    public static final String ACTION_PRINT_TEXT = "ks.cloud.printer.action.PRINT_TEXT";
    public static final String ACTION_PRINT_TICKET = "ks.cloud.printer.action.PRINT_TICKET";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_NUMBER = "number";
    public static final String EXTRA_TIME = "time";
    public static final String EXTRA_IMAGE_PATH = "image_path";
    public static final String EXTRA_IMAGE_BYTES = "image_bytes";
    public static final String EXTRA_LAYOUT_JSON = "layout_json";
    public static final String EXTRA_WAITING_COUNT = "waiting_count";

    public PrinterService() {
        super("PrinterService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            Log.e(TAG, "intent is null");
            return;
        }

        String action = intent.getAction();

        try {
            BixolonUsbPrinter printer = new BixolonUsbPrinter(this);

            if (ACTION_PRINT_TEXT.equals(action)) {
                String text = intent.getStringExtra(EXTRA_TEXT);

                if (TextUtils.isEmpty(text)) {
                    Log.e(TAG, "print text is empty");
                    return;
                }

                printer.printText(text);
                Log.d(TAG, "text print completed");
                return;
            }

            if (ACTION_PRINT_TICKET.equals(action)) {
                String title = intent.getStringExtra(EXTRA_TITLE);
                String number = intent.getStringExtra(EXTRA_NUMBER);
                String waitingCount = intent.getStringExtra(EXTRA_WAITING_COUNT);

                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date());

                Date now = new Date();
                String dateText = new SimpleDateFormat("yyyy년MM월dd일", Locale.KOREA).format(now);
                String timeText = new SimpleDateFormat("HH시mm분", Locale.KOREA).format(now);

                boolean useTestLogo = intent.getBooleanExtra("use_test_logo", false);
                boolean useTestLayout = intent.getBooleanExtra("use_test_layout", false);

                Log.d(TAG, "title=" + title);
                Log.d(TAG, "number=" + number);
                Log.d(TAG, "time=" + time);
                Log.d(TAG, "useTestLogo=" + useTestLogo);
                Log.d(TAG, "useTestLayout=" + useTestLayout);
                Log.d(TAG, "waitingCount=" + waitingCount);

                byte[] imageBytes = intent.getByteArrayExtra(EXTRA_IMAGE_BYTES);

                Bitmap logo = null;

                if (imageBytes != null && imageBytes.length > 0) {
                    logo = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

                    if (logo == null) {
                        Log.e(TAG, "decodeByteArray failed, size=" + imageBytes.length);
                    } else {
                        Log.d(TAG, "decodeByteArray success: "
                                + logo.getWidth() + "x" + logo.getHeight()
                                + ", size=" + imageBytes.length);
                    }
                } else {
                    Log.d(TAG, "imageBytes is empty");
                }

                if (logo == null && useTestLogo) {
                    logo = loadAssetLogo();
                }

                String layoutJson = intent.getStringExtra(EXTRA_LAYOUT_JSON);

                if (TextUtils.isEmpty(layoutJson) && useTestLayout) {
                    layoutJson = loadAssetText("ticket_layout.json");
                }

                if (TextUtils.isEmpty(layoutJson)) {
                    Log.d(TAG, "layoutJson is empty, use legacy printQueueTicket");
                    printer.printQueueTicket(title, number, time, logo);
                } else {
                    Log.d(TAG, "layoutJson loaded, size=" + layoutJson.length());
                    printer.printLayoutTicket(layoutJson, title, number, time, logo, waitingCount, dateText, timeText);
                }

                Log.d(TAG, "ticket print completed");
                return;
            }

            Log.e(TAG, "unknown action: " + action);

        } catch (Exception e) {
            Log.e(TAG, "print failed", e);
        }
    }

    private Bitmap loadAssetLogo() {
        Bitmap bitmap = decodeAssetLogo("logo.bmp");

        if (bitmap != null) {
            return bitmap;
        }

        bitmap = decodeAssetLogo("logo.png");

        if (bitmap != null) {
            return bitmap;
        }

        Log.e(TAG, "no asset logo found");
        return null;
    }

    private Bitmap decodeAssetLogo(String fileName) {
        InputStream is = null;

        try {
            is = getAssets().open(fileName);
            Bitmap bitmap = BitmapFactory.decodeStream(is);

            if (bitmap == null) {
                Log.e(TAG, "decode asset failed: " + fileName);
                return null;
            }

            Log.d(TAG, "decode asset success: "
                    + fileName + ", "
                    + bitmap.getWidth() + "x" + bitmap.getHeight());

            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "asset not readable: " + fileName);
            return null;

        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String loadAssetText(String fileName) {
        InputStream is = null;

        try {
            is = getAssets().open(fileName);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            byte[] buffer = new byte[4096];
            int read;

            while ((read = is.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }

            return new String(bos.toByteArray(), "UTF-8");

        } catch (Exception e) {
            Log.e(TAG, "load asset text failed: " + fileName, e);
            return null;

        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (Exception ignored) {
            }
        }
    }
}