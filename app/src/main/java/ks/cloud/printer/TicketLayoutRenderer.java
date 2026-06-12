package ks.cloud.printer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class TicketLayoutRenderer {

    private static final String TAG = "KS_LAYOUT";

    public static Bitmap render(
            String json,
            String title,
            String number,
            String time,
            Bitmap logo,
            String waitingCount,
            String dateText,
            String timeText
    ) throws Exception {

        JSONObject root = new JSONObject(json);

        int paperWidth = root.optInt("paperWidth", 384);
        int height = root.optInt("height", 560);

        Bitmap bitmap = Bitmap.createBitmap(paperWidth, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        int offsetX = root.optInt("offsetX", 0);
        int offsetY = root.optInt("offsetY", 0);
        canvas.translate(offsetX, offsetY);

        JSONArray items = root.getJSONArray("items");

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String type = item.optString("type");

            if ("text".equals(type)) {
                drawText(canvas, item, title, number, time, waitingCount, dateText, timeText);
            } else if ("rect".equals(type)) {
                drawRect(canvas, item);
            } else if ("line".equals(type)) {
                drawLine(canvas, item);
            } else if ("image".equals(type)) {
                drawImage(canvas, item, logo);
            } else {
                Log.e(TAG, "unknown item type: " + type);
            }
        }

        return trimBottomWhiteSpace(bitmap, 0);
    }

    private static Bitmap trimBottomWhiteSpace(Bitmap src, int paddingBottom) {
        if (src == null) {
            return null;
        }

        int width = src.getWidth();
        int height = src.getHeight();

        int lastContentY = -1;

        for (int y = height - 1; y >= 0; y--) {
            boolean hasContent = false;

            for (int x = 0; x < width; x++) {
                int pixel = src.getPixel(x, y);

                if (pixel != Color.WHITE) {
                    hasContent = true;
                    break;
                }
            }

            if (hasContent) {
                lastContentY = y;
                break;
            }
        }

        if (lastContentY < 0) {
            return src;
        }

        int croppedHeight = Math.min(height, lastContentY + 1 + paddingBottom);

        if (croppedHeight <= 0 || croppedHeight >= height) {
            return src;
        }

        return Bitmap.createBitmap(src, 0, 0, width, croppedHeight);
    }

    private static void drawText(
            Canvas canvas,
            JSONObject item,
            String title,
            String number,
            String time,
            String waitingCount,
            String dateText,
            String timeText
    ) {
        String text = item.optString("text", "");
        text = replaceVars(text, title, number, time, waitingCount, dateText, timeText);

        int x = item.optInt("x", 0);
        int y = item.optInt("y", 0);
        int fontSize = item.optInt("fontSize", 24);
        int minFontSize = item.optInt("minFontSize", 16);
        int maxWidth = item.optInt("maxWidth", 0);
        boolean bold = item.optBoolean("bold", false);
        String align = item.optString("align", "left");

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(fontSize);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));

        if ("center".equals(align)) {
            paint.setTextAlign(Paint.Align.CENTER);
        } else if ("right".equals(align)) {
            paint.setTextAlign(Paint.Align.RIGHT);
        } else {
            paint.setTextAlign(Paint.Align.LEFT);
        }

        if (maxWidth > 0) {
            while (paint.measureText(text) > maxWidth && paint.getTextSize() > minFontSize) {
                paint.setTextSize(paint.getTextSize() - 2);
            }
        }

        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = y - ((fm.ascent + fm.descent) / 2);

        canvas.drawText(text, x, baseline, paint);
    }

    private static void drawRect(Canvas canvas, JSONObject item) {
        int left = item.optInt("left", 0);
        int top = item.optInt("top", 0);
        int right = item.optInt("right", 0);
        int bottom = item.optInt("bottom", 0);
        int strokeWidth = item.optInt("strokeWidth", 2);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);

        canvas.drawRect(left, top, right, bottom, paint);
    }

    private static void drawLine(Canvas canvas, JSONObject item) {
        int x1 = item.optInt("x1", 0);
        int y1 = item.optInt("y1", 0);
        int x2 = item.optInt("x2", 0);
        int y2 = item.optInt("y2", 0);
        int strokeWidth = item.optInt("strokeWidth", 2);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(strokeWidth);

        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    private static void drawImage(Canvas canvas, JSONObject item, Bitmap logo) {
        String source = item.optString("source", "");

        if (!"logo".equals(source)) {
            Log.e(TAG, "unsupported image source: " + source);
            return;
        }

        if (logo == null) {
            Log.e(TAG, "logo bitmap is null");
            return;
        }

        int x = item.optInt("x", 0);
        int y = item.optInt("y", 0);
        int maxWidth = item.optInt("maxWidth", logo.getWidth());
        String align = item.optString("align", "left");

        Bitmap scaled = scaleToFitWidth(logo, maxWidth);

        int left;

        if ("center".equals(align)) {
            left = x - (scaled.getWidth() / 2);
        } else if ("right".equals(align)) {
            left = x - scaled.getWidth();
        } else {
            left = x;
        }

        canvas.drawBitmap(scaled, left, y, null);
    }

    private static Bitmap scaleToFitWidth(Bitmap src, int maxWidth) {
        if (src == null) {
            return null;
        }

        if (src.getWidth() <= maxWidth) {
            return src;
        }

        float ratio = (float) maxWidth / (float) src.getWidth();
        int newHeight = Math.max(1, Math.round(src.getHeight() * ratio));

        return Bitmap.createScaledBitmap(src, maxWidth, newHeight, true);
    }

    private static String replaceVars(
            String text,
            String title,
            String number,
            String time,
            String waitingCount,
            String dateText,
            String timeText
    ) {
        if (TextUtils.isEmpty(title)) title = "";
        if (TextUtils.isEmpty(number)) number = "";
        if (TextUtils.isEmpty(time)) time = "";
        if (TextUtils.isEmpty(waitingCount)) waitingCount = "";
        if (TextUtils.isEmpty(dateText)) dateText = "";
        if (TextUtils.isEmpty(timeText)) timeText = "";

        return text
                .replace("${title}", title)
                .replace("${number}", number)
                .replace("${time}", time)
                .replace("${waiting_count}", waitingCount)
                .replace("${date_text}", dateText)
                .replace("${time_text}", timeText);
    }
}
