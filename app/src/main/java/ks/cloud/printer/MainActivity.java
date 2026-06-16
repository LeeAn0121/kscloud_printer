package ks.cloud.printer;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbConfiguration;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.lang.reflect.Constructor;

public class MainActivity extends Activity {

    private static final String TAG = "KS_PRINTER";
    private static final String ACTION_USB_PERMISSION = "ks.cloud.printer.USB_PERMISSION";
    private static final String EXTRA_RUN_TEST_PRINT = "run_test_print";

    private static final int BIXOLON_VENDOR_ID = 5380;
    private static final int BIXOLON_PRODUCT_ID = 276;

    private UsbManager usbManager;
    private UsbDevice printerDevice;
    private TextView logView;
    private boolean receiverRegistered;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    log("USB permission granted");
                    printerDevice = device;
                    startTestPrintIfRequested();
                } else {
                    log("USB permission denied");
                }

                finish();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("USB attached");
                findPrinter();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("USB detached");
                printerDevice = null;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);
        receiverRegistered = true;

        findPrinter();
    }

    private void findPrinter() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

        log("USB device count: " + deviceList.size());

        for (UsbDevice device : deviceList.values()) {
            log("Device: " + device.getDeviceName()
                    + ", VID=" + device.getVendorId()
                    + ", PID=" + device.getProductId()
                    + ", Class=" + device.getDeviceClass());

            if (device.getVendorId() == BIXOLON_VENDOR_ID &&
                    device.getProductId() == BIXOLON_PRODUCT_ID) {

                printerDevice = device;
                log("BIXOLON printer found");

                if (!usbManager.hasPermission(device)) {
                    requestPermission(device);
                } else {
                    log("Already has USB permission");
                    startTestPrintIfRequested();
                    finish();
                }

                return;
            }
        }

        log("BIXOLON printer not found");
        finish();
    }

    private void requestPermission(UsbDevice device) {
        log("Request USB permission");

        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(ACTION_USB_PERMISSION),
                0
        );

        usbManager.requestPermission(device, permissionIntent);
    }

    private void startTestPrintIfRequested() {
        Intent request = getIntent();

        if (request == null || !request.getBooleanExtra(EXTRA_RUN_TEST_PRINT, false)) {
            return;
        }

        Intent printIntent = new Intent(this, PrinterService.class);
        printIntent.setAction(PrinterService.ACTION_PRINT_TICKET);
        printIntent.putExtra(PrinterService.EXTRA_TITLE, request.getStringExtra(PrinterService.EXTRA_TITLE));
        printIntent.putExtra(PrinterService.EXTRA_NUMBER, request.getStringExtra(PrinterService.EXTRA_NUMBER));
        printIntent.putExtra(PrinterService.EXTRA_WAITING_COUNT, request.getStringExtra(PrinterService.EXTRA_WAITING_COUNT));
        printIntent.putExtra("use_test_logo", true);
        printIntent.putExtra("use_test_layout", true);

        startService(printIntent);
        log("test print service started");
    }

    private void printTest(UsbDevice device) {
        log("printTest start");
        log("DeviceName=" + device.getDeviceName());
        log("VID=" + device.getVendorId()
                + ", PID=" + device.getProductId()
                + ", DeviceClass=" + device.getDeviceClass());
        log("InterfaceCount=" + device.getInterfaceCount());
        log("ConfigurationCount=" + device.getConfigurationCount());

        UsbInterface printerInterface = null;
        UsbEndpoint outEndpoint = null;

        // 1차: device.getInterface() 기준 탐색
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);

            log("[DeviceInterface] index=" + i
                    + ", id=" + intf.getId()
                    + ", class=" + intf.getInterfaceClass()
                    + ", subclass=" + intf.getInterfaceSubclass()
                    + ", protocol=" + intf.getInterfaceProtocol()
                    + ", endpointCount=" + intf.getEndpointCount());

            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);

                log("  EP index=" + e
                        + ", address=" + ep.getAddress()
                        + ", direction=" + ep.getDirection()
                        + ", type=" + ep.getType()
                        + ", maxPacket=" + ep.getMaxPacketSize());

                if (ep.getDirection() == UsbConstants.USB_DIR_OUT &&
                        ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    printerInterface = intf;
                    outEndpoint = ep;
                    log("OUT endpoint selected by device interface");
                }
            }
        }

        // 2차: configuration 기준 탐색
        if (printerInterface == null || outEndpoint == null) {
            log("Try configuration-based interface scan");

            for (int c = 0; c < device.getConfigurationCount(); c++) {
                UsbConfiguration config = device.getConfiguration(c);

                log("[Configuration] index=" + c
                        + ", id=" + config.getId()
                        + ", interfaceCount=" + config.getInterfaceCount());

                for (int i = 0; i < config.getInterfaceCount(); i++) {
                    UsbInterface intf = config.getInterface(i);

                    log("[ConfigInterface] index=" + i
                            + ", id=" + intf.getId()
                            + ", class=" + intf.getInterfaceClass()
                            + ", subclass=" + intf.getInterfaceSubclass()
                            + ", protocol=" + intf.getInterfaceProtocol()
                            + ", endpointCount=" + intf.getEndpointCount());

                    for (int e = 0; e < intf.getEndpointCount(); e++) {
                        UsbEndpoint ep = intf.getEndpoint(e);

                        log("  EP index=" + e
                                + ", address=" + ep.getAddress()
                                + ", direction=" + ep.getDirection()
                                + ", type=" + ep.getType()
                                + ", maxPacket=" + ep.getMaxPacketSize());

                        // 일반 조건
                        if (ep.getDirection() == UsbConstants.USB_DIR_OUT &&
                                ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            printerInterface = intf;
                            outEndpoint = ep;
                            log("OUT endpoint selected by configuration scan");
                        }

                        // BK3-21에서 확인된 OUT endpoint address=2 강제 선택
                        if (ep.getAddress() == 2) {
                            printerInterface = intf;
                            outEndpoint = ep;
                            log("OUT endpoint selected by fixed address 0x02");
                        }
                    }
                }
            }
        }

        if (printerInterface == null || outEndpoint == null) {
            log("OUT endpoint not found by Android API");
            log("Try fallback fake interface/endpoint");

            try {
                printerInterface = createFakePrinterInterface();
                outEndpoint = createFakeBulkOutEndpoint();
                log("Fallback interface/endpoint created");
            } catch (Exception e) {
                log("Fallback create failed: " + e.getMessage());
                Log.e(TAG, "Fallback create failed", e);
                return;
            }
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);

        if (connection == null) {
            log("openDevice failed");
            return;
        }

        log("openDevice success");

        boolean claimed = connection.claimInterface(printerInterface, true);

        if (!claimed) {
            log("claimInterface failed");
            connection.close();
            return;
        }

        log("claimInterface success");

        try {
            log("Start print");

            send(connection, outEndpoint, new byte[]{0x1B, 0x40}); // ESC @
            send(connection, outEndpoint, "BIXOLON BK3-21 TEST\n".getBytes(Charset.forName("US-ASCII")));
            send(connection, outEndpoint, "ANDROID 5.1.1 USB RAW PRINT\n".getBytes(Charset.forName("US-ASCII")));
            send(connection, outEndpoint, "------------------------\n".getBytes(Charset.forName("US-ASCII")));
            send(connection, outEndpoint, "PRINT OK\n\n\n".getBytes(Charset.forName("US-ASCII")));

            // Partial cut
            send(connection, outEndpoint, new byte[]{0x1D, 0x56, 0x42, 0x00});

            log("Print command sent");

        } catch (Exception e) {
            log("Print failed: " + e.getMessage());
            Log.e(TAG, "Print failed", e);
        } finally {
            connection.releaseInterface(printerInterface);
            connection.close();
            log("USB connection closed");
        }
    }

    private UsbInterface createFakePrinterInterface() throws Exception {
        Constructor<UsbInterface> constructor =
                UsbInterface.class.getDeclaredConstructor(
                        int.class,     // id
                        int.class,     // alternateSetting
                        String.class,  // name
                        int.class,     // class
                        int.class,     // subclass
                        int.class      // protocol
                );

        constructor.setAccessible(true);

        return constructor.newInstance(
                0,      // interface id
                0,      // alternate setting
                null,   // name
                7,      // USB Printer Class
                1,      // subclass
                2       // protocol
        );
    }

    private UsbEndpoint createFakeBulkOutEndpoint() throws Exception {
        Constructor<UsbEndpoint> constructor =
                UsbEndpoint.class.getDeclaredConstructor(
                        int.class, // address
                        int.class, // attributes
                        int.class, // maxPacketSize
                        int.class  // interval
                );

        constructor.setAccessible(true);

        return constructor.newInstance(
                0x02,   // OUT endpoint address
                UsbConstants.USB_ENDPOINT_XFER_BULK,
                64,
                0
        );
    }

    private void send(UsbDeviceConnection connection, UsbEndpoint endpoint, byte[] data) throws Exception {
        int offset = 0;

        while (offset < data.length) {
            int length = Math.min(endpoint.getMaxPacketSize(), data.length - offset);
            byte[] packet = new byte[length];

            System.arraycopy(data, offset, packet, 0, length);

            int result = connection.bulkTransfer(endpoint, packet, length, 3000);

            if (result < 0) {
                throw new Exception("bulkTransfer failed, result=" + result);
            }

            offset += result;
        }
    }

    private void log(String message) {
        Log.d(TAG, message);

        if (logView != null) {
            logView.append(message + "\n");
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }

        super.onDestroy();
    }
}
