package ks.cloud.printer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.nio.charset.Charset;
import java.util.HashMap;

public class BixolonUsbPrinter {

    private static final String TAG = "KS_PRINTER";

    private static final int BIXOLON_VENDOR_ID = 5380;
    private static final int BIXOLON_PRODUCT_ID = 276;

    private final Context context;
    private final UsbManager usbManager;

    public BixolonUsbPrinter(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    public UsbDevice findPrinterDevice() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

        Log.d(TAG, "USB device count: " + deviceList.size());

        for (UsbDevice device : deviceList.values()) {
            Log.d(TAG, "Device: " + device.getDeviceName()
                    + ", VID=" + device.getVendorId()
                    + ", PID=" + device.getProductId()
                    + ", Class=" + device.getDeviceClass());

            if (device.getVendorId() == BIXOLON_VENDOR_ID &&
                    device.getProductId() == BIXOLON_PRODUCT_ID) {
                Log.d(TAG, "BIXOLON printer found");
                return device;
            }
        }

        Log.e(TAG, "BIXOLON printer not found");
        return null;
    }

    public void printText(String text) throws Exception {
        UsbDevice device = findPrinterDevice();

        if (device == null) {
            throw new Exception("BIXOLON printer not found");
        }

        if (!usbManager.hasPermission(device)) {
            throw new Exception("USB permission not granted");
        }

        UsbDeviceConnection connection = null;
        UsbInterface printerInterface = null;
        UsbEndpoint outEndpoint = null;
        boolean claimed = false;

        EndpointPair pair = findOutEndpoint(device);

        if (pair != null) {
            printerInterface = pair.usbInterface;
            outEndpoint = pair.outEndpoint;
        }

        if (printerInterface == null || outEndpoint == null) {
            Log.d(TAG, "OUT endpoint not found by Android API");
            Log.d(TAG, "Try fallback fake interface/endpoint");

            printerInterface = createFakePrinterInterface();
            outEndpoint = createFakeBulkOutEndpoint();

            Log.d(TAG, "Fallback interface/endpoint created");
        }

        try {
            connection = usbManager.openDevice(device);

            if (connection == null) {
                throw new Exception("openDevice failed");
            }

            Log.d(TAG, "openDevice success");

            claimed = connection.claimInterface(printerInterface, true);

            if (claimed) {
                Log.d(TAG, "claimInterface success");
            } else {
                Log.e(TAG, "claimInterface failed, continue bulkTransfer fallback");
            }

            send(connection, outEndpoint, new byte[]{0x1B, 0x40});
            send(connection, outEndpoint, text.getBytes(Charset.forName("US-ASCII")));

            if (!text.endsWith("\n")) {
                send(connection, outEndpoint, "\n".getBytes(Charset.forName("US-ASCII")));
            }

            send(connection, outEndpoint, new byte[]{0x0A, 0x0A, 0x0A});
            send(connection, outEndpoint, new byte[]{0x1D, 0x56, 0x42, 0x00});

            Log.d(TAG, "text print command sent");

        } finally {
            if (connection != null) {
                if (claimed) {
                    try {
                        connection.releaseInterface(printerInterface);
                        Log.d(TAG, "releaseInterface success");
                    } catch (Exception e) {
                        Log.e(TAG, "releaseInterface failed", e);
                    }
                }

                connection.close();
                Log.d(TAG, "USB connection closed");
            }
        }
    }

    public void printQueueTicket(String title, String number, String time, Bitmap logo) throws Exception {
        Bitmap bitmap = buildQueueTicketBitmap(title, number, time, logo);
        printBitmap(bitmap);
    }

    public void printBitmap(Bitmap bitmap) throws Exception {
        UsbDevice device = findPrinterDevice();

        if (device == null) {
            throw new Exception("BIXOLON printer not found");
        }

        if (!usbManager.hasPermission(device)) {
            throw new Exception("USB permission not granted");
        }

        UsbDeviceConnection connection = null;
        UsbInterface printerInterface = null;
        UsbEndpoint outEndpoint = null;
        boolean claimed = false;

        EndpointPair pair = findOutEndpoint(device);

        if (pair != null) {
            printerInterface = pair.usbInterface;
            outEndpoint = pair.outEndpoint;
        }

        if (printerInterface == null || outEndpoint == null) {
            Log.d(TAG, "OUT endpoint not found by Android API");
            Log.d(TAG, "Try fallback fake interface/endpoint");

            printerInterface = createFakePrinterInterface();
            outEndpoint = createFakeBulkOutEndpoint();

            Log.d(TAG, "Fallback interface/endpoint created");
        }

        try {
            connection = usbManager.openDevice(device);

            if (connection == null) {
                throw new Exception("openDevice failed");
            }

            Log.d(TAG, "openDevice success");

            claimed = connection.claimInterface(printerInterface, true);

            if (claimed) {
                Log.d(TAG, "claimInterface success");
            } else {
                Log.e(TAG, "claimInterface failed, continue bulkTransfer fallback");
            }

            send(connection, outEndpoint, new byte[]{0x1B, 0x40});
            send(connection, outEndpoint, bitmapToRasterBytes(bitmap));
            send(connection, outEndpoint, new byte[]{0x1D, 0x56, 0x42, 0x00});

            Log.d(TAG, "bitmap print command sent");

        } finally {
            if (connection != null) {
                if (claimed) {
                    try {
                        connection.releaseInterface(printerInterface);
                        Log.d(TAG, "releaseInterface success");
                    } catch (Exception e) {
                        Log.e(TAG, "releaseInterface failed", e);
                    }
                }

                connection.close();
                Log.d(TAG, "USB connection closed");
            }
        }
    }

//    private Bitmap buildQueueTicketBitmap(String title, String number, String time, Bitmap logo) {
//        int paperWidth = 384;
//        int height = 650;
//
//        Bitmap bitmap = Bitmap.createBitmap(paperWidth, height, Bitmap.Config.ARGB_8888);
//        Canvas canvas = new Canvas(bitmap);
//        canvas.drawColor(Color.WHITE);
//
//        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
//        paint.setColor(Color.BLACK);
//
//        int y = 20;
//
//        if (logo != null) {
//            Bitmap scaledLogo = scaleToFitWidth(logo, 180);
//            int x = (paperWidth - scaledLogo.getWidth()) / 2;
//            canvas.drawBitmap(scaledLogo, x, y, null);
//            y += scaledLogo.getHeight() + 25;
//        }
//
//        paint.setTextAlign(Paint.Align.CENTER);
//        paint.setTypeface(Typeface.DEFAULT_BOLD);
//        paint.setTextSize(32);
//        canvas.drawText(title != null ? title : "대기번호", paperWidth / 2, y + 35, paint);
//        y += 70;
//
//        paint.setStrokeWidth(2);
//        canvas.drawLine(20, y, paperWidth - 20, y, paint);
//        y += 35;
//
//        paint.setTextSize(88);
//        paint.setTypeface(Typeface.DEFAULT_BOLD);
//        canvas.drawText(number != null ? number : "A-001", paperWidth / 2, y + 90, paint);
//        y += 125;
//
//        canvas.drawLine(20, y, paperWidth - 20, y, paint);
//        y += 45;
//
//        paint.setTypeface(Typeface.DEFAULT);
//        paint.setTextSize(24);
//
//        if (time != null && time.length() > 0) {
//            canvas.drawText(time, paperWidth / 2, y + 26, paint);
//            y += 45;
//        }
//
//        paint.setTextSize(24);
//        canvas.drawText("감사합니다", paperWidth / 2, y + 28, paint);
//        y += 50;
//
//        return Bitmap.createBitmap(bitmap, 0, 0, paperWidth, y + 20);
//    }

    private Bitmap buildQueueTicketBitmap(String title, String number, String time, Bitmap logo) {
        if (title == null || title.length() == 0) {
            title = "대기번호";
        }

        if (number == null || number.length() == 0) {
            number = "A-001";
        }

        if (time == null || time.length() == 0) {
            time = "";
        }

        int paperWidth = 384;
        int margin = 16;

        Bitmap scaledLogo = null;

        if (logo != null) {
            scaledLogo = scaleToFitWidth(logo, 220);
        }

        int logoHeight = 0;

        if (scaledLogo != null) {
            logoHeight = scaledLogo.getHeight();
        }

        int boxTop = 24;
        int boxHeight = 280;

        int infoTop = boxTop + boxHeight + 28;
        int infoHeight = 110;

        int logoTop = infoTop + infoHeight + 10;
        int bottomPadding = 28;

        int bitmapHeight = logoTop + logoHeight + bottomPadding;

        if (bitmapHeight < 470) {
            bitmapHeight = 470;
        }

        Bitmap bitmap = Bitmap.createBitmap(paperWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.CENTER);

        // 전체 대기번호 박스
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        canvas.drawRect(
                margin,
                boxTop,
                paperWidth - margin,
                boxTop + boxHeight,
                paint
        );

        // 제목/번호 구분선
        paint.setStrokeWidth(2);
        canvas.drawLine(
                margin + 14,
                boxTop + 88,
                paperWidth - margin - 14,
                boxTop + 88,
                paint
        );

        // 제목: 대기번호
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(44);
        drawCenteredText(canvas, title, paint, paperWidth / 2, boxTop + 46);

        // 번호: 크게 출력
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(118);

        while (paint.measureText(number) > paperWidth - 52 && paint.getTextSize() > 76) {
            paint.setTextSize(paint.getTextSize() - 4);
        }

        drawCenteredText(canvas, number, paint, paperWidth / 2, boxTop + 185);

        // 발행시각
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        paint.setTextSize(26);
        drawCenteredText(canvas, time, paint, paperWidth / 2, infoTop + 24);

        // 안내 문구
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(30);
        drawCenteredText(canvas, "감사합니다", paint, paperWidth / 2, infoTop + 72);

        // 로고: 맨 하단 정가운데
        if (scaledLogo != null) {
            int logoLeft = (paperWidth - scaledLogo.getWidth()) / 2;
            canvas.drawBitmap(scaledLogo, logoLeft, logoTop, null);
        }

        return bitmap;
    }

    private void drawCenteredText(Canvas canvas, String text, Paint paint, float centerX, float centerY) {
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float baseline = centerY - ((fontMetrics.ascent + fontMetrics.descent) / 2);
        canvas.drawText(text, centerX, baseline, paint);
    }

    private Bitmap scaleToFitWidth(Bitmap src, int maxWidth) {
        if (src.getWidth() <= maxWidth) {
            return src;
        }

        float ratio = (float) maxWidth / (float) src.getWidth();
        int newWidth = maxWidth;
        int newHeight = (int) (src.getHeight() * ratio);

        return Bitmap.createScaledBitmap(src, newWidth, newHeight, true);
    }

    private byte[] bitmapToRasterBytes(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int widthBytes = (width + 7) / 8;
        byte[] imageBytes = new byte[8 + widthBytes * height];

        imageBytes[0] = 0x1D;
        imageBytes[1] = 0x76;
        imageBytes[2] = 0x30;
        imageBytes[3] = 0x00;
        imageBytes[4] = (byte) (widthBytes & 0xFF);
        imageBytes[5] = (byte) ((widthBytes >> 8) & 0xFF);
        imageBytes[6] = (byte) (height & 0xFF);
        imageBytes[7] = (byte) ((height >> 8) & 0xFF);

        int index = 8;

        for (int y = 0; y < height; y++) {
            for (int xByte = 0; xByte < widthBytes; xByte++) {
                byte b = 0;

                for (int bit = 0; bit < 8; bit++) {
                    int x = xByte * 8 + bit;

                    if (x < width) {
                        int pixel = bitmap.getPixel(x, y);

                        int r = (pixel >> 16) & 0xff;
                        int g = (pixel >> 8) & 0xff;
                        int bl = pixel & 0xff;

                        int gray = (r + g + bl) / 3;

                        if (gray < 180) {
                            b |= (byte) (0x80 >> bit);
                        }
                    }
                }

                imageBytes[index++] = b;
            }
        }

        return imageBytes;
    }

    private EndpointPair findOutEndpoint(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);

            Log.d(TAG, "[DeviceInterface] index=" + i
                    + ", id=" + intf.getId()
                    + ", class=" + intf.getInterfaceClass()
                    + ", subclass=" + intf.getInterfaceSubclass()
                    + ", protocol=" + intf.getInterfaceProtocol()
                    + ", endpointCount=" + intf.getEndpointCount());

            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);

                Log.d(TAG, "  EP index=" + e
                        + ", address=" + ep.getAddress()
                        + ", direction=" + ep.getDirection()
                        + ", type=" + ep.getType()
                        + ", maxPacket=" + ep.getMaxPacketSize());

                if (isBulkOutEndpoint(ep) || ep.getAddress() == 0x02) {
                    Log.d(TAG, "OUT endpoint selected by device interface");
                    return new EndpointPair(intf, ep);
                }
            }
        }

        for (int c = 0; c < device.getConfigurationCount(); c++) {
            UsbConfiguration config = device.getConfiguration(c);

            Log.d(TAG, "[Configuration] index=" + c
                    + ", id=" + config.getId()
                    + ", interfaceCount=" + config.getInterfaceCount());

            for (int i = 0; i < config.getInterfaceCount(); i++) {
                UsbInterface intf = config.getInterface(i);

                Log.d(TAG, "[ConfigInterface] index=" + i
                        + ", id=" + intf.getId()
                        + ", class=" + intf.getInterfaceClass()
                        + ", subclass=" + intf.getInterfaceSubclass()
                        + ", protocol=" + intf.getInterfaceProtocol()
                        + ", endpointCount=" + intf.getEndpointCount());

                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);

                    Log.d(TAG, "  EP index=" + e
                            + ", address=" + ep.getAddress()
                            + ", direction=" + ep.getDirection()
                            + ", type=" + ep.getType()
                            + ", maxPacket=" + ep.getMaxPacketSize());

                    if (isBulkOutEndpoint(ep) || ep.getAddress() == 0x02) {
                        Log.d(TAG, "OUT endpoint selected by configuration scan");
                        return new EndpointPair(intf, ep);
                    }
                }
            }
        }

        return null;
    }

    private boolean isBulkOutEndpoint(UsbEndpoint ep) {
        return ep.getDirection() == UsbConstants.USB_DIR_OUT &&
                ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK;
    }

    private UsbInterface createFakePrinterInterface() throws Exception {
        Constructor<UsbInterface> constructor =
                UsbInterface.class.getDeclaredConstructor(
                        int.class,
                        int.class,
                        String.class,
                        int.class,
                        int.class,
                        int.class
                );

        constructor.setAccessible(true);

        return constructor.newInstance(
                0,
                0,
                null,
                7,
                1,
                2
        );
    }

    private UsbEndpoint createFakeBulkOutEndpoint() throws Exception {
        Constructor<UsbEndpoint> constructor =
                UsbEndpoint.class.getDeclaredConstructor(
                        int.class,
                        int.class,
                        int.class,
                        int.class
                );

        constructor.setAccessible(true);

        return constructor.newInstance(
                0x02,
                UsbConstants.USB_ENDPOINT_XFER_BULK,
                64,
                0
        );
    }

    private void send(UsbDeviceConnection connection, UsbEndpoint endpoint, byte[] data) throws Exception {
        int offset = 0;
        int maxPacketSize = endpoint.getMaxPacketSize();

        if (maxPacketSize <= 0) {
            maxPacketSize = 64;
        }

        while (offset < data.length) {
            int length = Math.min(maxPacketSize, data.length - offset);

            byte[] packet = new byte[length];
            System.arraycopy(data, offset, packet, 0, length);

            int result = connection.bulkTransfer(endpoint, packet, length, 3000);

            Log.d(TAG, "bulkTransfer result=" + result + ", length=" + length);

            if (result < 0) {
                throw new Exception("bulkTransfer failed, result=" + result);
            }

            offset += result;
        }
    }

    public void printNvImage(int imageNumber) throws Exception {
        UsbDevice device = findPrinterDevice();

        if (device == null) {
            Log.e(TAG, "printer not found");
            return;
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);

        if (connection == null) {
            Log.e(TAG, "openDevice failed");
            return;
        }

        EndpointPair pair = findOutEndpoint(device);

        UsbInterface usbInterface = pair.usbInterface;
        UsbEndpoint outEndpoint = pair.outEndpoint;

        try {
            if (usbInterface != null) {
                connection.claimInterface(usbInterface, true);
            }

            // ESC @ : printer initialize
            send(connection, outEndpoint, new byte[]{0x1B, 0x40});

            byte n = (byte) imageNumber;

            // FS p n m : print NV bit image
            // n = image number
            // m = 0 normal size
            send(connection, outEndpoint, new byte[]{
                    0x1C, 0x70, n, 0x00
            });

            // line feed
            send(connection, outEndpoint, new byte[]{0x0A, 0x0A});

            Log.d(TAG, "NV image print command sent: " + imageNumber);

        } finally {
            try {
                if (usbInterface != null) {
                    connection.releaseInterface(usbInterface);
                }
            } catch (Exception ignored) {
            }

            connection.close();
        }
    }

    public void printLayoutTicket(
            String layoutJson,
            String title,
            String number,
            String time,
            Bitmap logo,
            String waitingCount,
            String dateText,
            String timeText
    ) throws Exception {
        Bitmap bitmap = TicketLayoutRenderer.render(
                layoutJson,
                title,
                number,
                time,
                logo,
                waitingCount,
                dateText,
                timeText
        );

        printBitmap(bitmap);
    }

    private static class EndpointPair {
        UsbInterface usbInterface;
        UsbEndpoint outEndpoint;

        EndpointPair(UsbInterface usbInterface, UsbEndpoint outEndpoint) {
            this.usbInterface = usbInterface;
            this.outEndpoint = outEndpoint;
        }
    }
}
