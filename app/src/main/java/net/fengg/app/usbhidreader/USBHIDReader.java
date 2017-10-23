package net.fengg.app.usbhidreader;

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
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;

public class USBHIDReader {
	String TAG = "USBHIDReader";
	static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

	UsbManager usbManager;
	UsbDevice usbDevice = null;
	UsbInterface usbHidInterface = null;
	UsbEndpoint usbHidRead = null;
	UsbEndpoint usbHidWrite = null;
	UsbDeviceConnection usbHidConnection;
	private final int vendorID = 0;
	private final int productID = 0;

	PendingIntent permissionIntent;

	Context context;
	private int packetSize;
	private USBThreadDataReceiver usbThreadDataReceiver;
	private IReceiveDataListener iRlistener;

	public USBHIDReader(Context context)
	{
		this.context = context;
		usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
	}

	public boolean open() {
		boolean result = enumerateDevice();
		if(result) {
			return true;
		} else {
			int i = 3;
			while(!result) {
				if(i < 0) {
					break;
				}
				result = enumerateDevice();
				i--;
				try {
					Thread.currentThread().sleep(200);
				} catch (Exception e) {
					Log.i(TAG, e.toString());
				}
			}
		}
		return result;
	}

	private boolean enumerateDevice() {
		if(null == usbManager) {
			Log.i(TAG, "usbManager is null");
			return false;
		}
		HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
		if (deviceList.isEmpty()) {
			Log.i(TAG, "deviceList is empty");
			return false;
		}

		Iterator<UsbDevice> deviceIterator = deviceList.values()
				.iterator();
		while (deviceIterator.hasNext()) {
			UsbDevice device = deviceIterator.next();
			Log.i(TAG, "DeviceInfo: " + device.getVendorId() + " , "
					+ device.getProductId());
			if (device.getVendorId() == vendorID && device.getProductId() == productID) {
				if(usbManager.hasPermission(device)) {
					usbDevice = device;
					if(findInterface()) {
						if(assignEndpoint()) {
							return openDevice();
						}
					}
				} else {
					permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);
					IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
					context.registerReceiver(usbReceiver, filter);
					usbManager.requestPermission(usbDevice, permissionIntent);
				}
				break;
			}
		}
		return false;
	}

	private boolean findInterface() {
		if (usbDevice == null) {
			return false;
		}
			Log.d(TAG, "interfaceCounts : " + usbDevice.getInterfaceCount());
			for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
				UsbInterface intf = usbDevice.getInterface(i);
				if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
//						&& intf.getInterfaceSubclass() == 6
//						&& intf.getInterfaceProtocol() == 80) {
					usbHidInterface = intf;
				}
			}
		return true;
	}

	private boolean assignEndpoint() {
		if (usbHidInterface == null) {
			return false;
		}
		for (int i = 0; i < usbHidInterface.getEndpointCount(); i++) {
			UsbEndpoint ep = usbHidInterface.getEndpoint(i);
			if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
				if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
					usbHidWrite = ep;
				} else {
					usbHidRead = ep;
					packetSize = usbHidRead.getMaxPacketSize();
				}
			}
		}
		return true;
	}

	private boolean openDevice() {
		if (usbHidInterface == null) {
			return false;
		}
			UsbDeviceConnection conn = null;
			if (usbManager.hasPermission(usbDevice)) {
				conn = usbManager.openDevice(usbDevice);
			}

			if (conn == null) {
				return false;
			}

			if (conn.claimInterface(usbHidInterface, true)) {
				usbHidConnection = conn;
				Log.d(TAG, "打开设备成功");
			} else {
				conn.close();
			}
		return true;

	}

	public void setUsbThreadDataReceiver() {
		usbThreadDataReceiver = new USBThreadDataReceiver();
		usbThreadDataReceiver.start();
	}

	public void stopRead() {
		if (usbThreadDataReceiver != null) {
			usbThreadDataReceiver.stopThis();
			usbThreadDataReceiver = null;
		}
	}

	public void close() {
		if (null == usbManager) {
			return;
		}
		if (usbManager.getDeviceList().isEmpty()) {
			return;
		}
		if (usbDevice != null) {
			return;
		}
		if (usbThreadDataReceiver != null) {
			usbThreadDataReceiver.stopThis();
			usbThreadDataReceiver = null;
		}

		usbHidConnection.releaseInterface(usbHidInterface);
		usbHidConnection.close();
		usbHidConnection = null;
		usbHidInterface = null;
		usbHidRead = null;
		usbHidWrite = null;
		usbDevice = null;

		context.unregisterReceiver(usbReceiver);
		Log.d(TAG, "USB connection closed");

	}

	public int sendData(String data, boolean sendAsString) {
		if (usbDevice != null && usbHidWrite != null && usbManager.hasPermission(usbDevice) &&
				null != data && !data.isEmpty()) {
			//format for self protocol
			byte[] out = null;
			if(sendAsString) {
				try {
					String str[] = data.split("[\\s]");
					out = new byte[str.length+2];
					out[0] = 0x04;
					out[1] = toByte(Integer.decode(Integer.toHexString(str.length)));
					for (int i = 0; i < str.length; i++) {
						out[i+2] = toByte(Integer.decode(str[i]));
					}
				} catch (Exception e) {

				}
			} else {
				out = data.getBytes();
				byte[] out1 = new byte[out.length + 2];
				out1[0] = 0x04;
				out1[1] = toByte(out.length);

				for (int i = 0; i < out.length; i++) {
					out1[i + 2] = out[i];
				}
				out = out1;
			}
			Log.i(TAG,"senddata:"+ bytes2HexString(out));
			int status = usbHidConnection.bulkTransfer(usbHidWrite, out, out.length, 250);
			return status;
		}else{
			return -1;
		}
	}

	private class USBThreadDataReceiver extends Thread {

		private volatile boolean isStopped;

		public USBThreadDataReceiver() {
		}

		@Override
		public void run() {
			try {
				if (usbHidConnection != null && usbHidRead != null) {
					while (!isStopped) {
						final byte[] buffer = new byte[packetSize];
						if (usbHidConnection != null && usbHidRead != null) {
							final int status = usbHidConnection.bulkTransfer(usbHidRead, buffer, packetSize, 100);
							if (status > 0) {
								int datalen = toInt(buffer[1]);
								final String s = new String(buffer, 2, datalen, "GBK");
								if (null != iRlistener) {
									iRlistener.onReceiveData(s);
								}
							}
						}
					}
				}
			} catch (Exception e) {
				Log.e(TAG, "Error in receive thread", e);
			}
		}

		public void stopThis() {
			isStopped = true;
		}
	}

	public interface IReceiveDataListener {
		void onReceiveData(String data);
	}

	public IReceiveDataListener getiRlistener() {
		return iRlistener;
	}

	public void setiRlistener(IReceiveDataListener iRlistener) {
		this.iRlistener = iRlistener;
	}

	private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (ACTION_USB_PERMISSION.equals(action)) {
				// broadcast is like an interrupt and works asynchronously with
				// the class, it must be synced just in case
				synchronized (this) {
					if (intent.getBooleanExtra(
							UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						UsbDevice device = (UsbDevice) intent
								.getParcelableExtra(UsbManager.EXTRA_DEVICE);
						if (device.getVendorId() == vendorID && device.getProductId() == productID) {
							if (usbManager.hasPermission(device)) {
								usbDevice = device;
								if (findInterface()) {
									if (assignEndpoint()) {
										openDevice();
									}
								}
							}
						}
					} else {
						Log.d(TAG, "Permission denied for USB device");
					}
				}
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				UsbDevice device = (UsbDevice) intent
						.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				if (null != device && usbDevice != null &&
						device.getVendorId() == vendorID && device.getProductId() == productID) {
						usbHidConnection.releaseInterface(usbHidInterface);
						usbHidConnection.close();
						usbHidConnection = null;
						usbDevice = null;

						if (usbThreadDataReceiver != null) {
							usbThreadDataReceiver.stopThis();
						}

						Log.d(TAG, "USB connection closed");
				}
			}
		}
	};

	private String listUsbDevices()
	{
		HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

		if(deviceList.size() == 0)
		{
			return "no usb devices found";
		}

		Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
		String returnValue = "";
		UsbInterface usbInterface;

		while(deviceIterator.hasNext())
		{
			UsbDevice device = deviceIterator.next();
			returnValue += "Name: " + device.getDeviceName();
			returnValue += "\nID: " + device.getDeviceId();
			returnValue += "\nProtocol: " + device.getDeviceProtocol();
			returnValue += "\nClass: " + device.getDeviceClass();
			returnValue += "\nSubclass: " + device.getDeviceSubclass();
			returnValue += "\nProduct ID: " + device.getProductId();
			returnValue += "\nVendor ID: " + device.getVendorId();
			returnValue += "\nInterface count: " + device.getInterfaceCount();

			for(int i = 0; i < device.getInterfaceCount(); i++)
			{
				usbInterface = device.getInterface(i);
				returnValue += "\n  Interface " + i;
				returnValue += "\n\tInterface ID: " + usbInterface.getId();
				returnValue += "\n\tClass: " + usbInterface.getInterfaceClass();
				returnValue += "\n\tProtocol: " + usbInterface.getInterfaceProtocol();
				returnValue += "\n\tSubclass: " + usbInterface.getInterfaceSubclass();
				returnValue += "\n\tEndpoint count: " + usbInterface.getEndpointCount();

				for(int j = 0; j < usbInterface.getEndpointCount(); j++)
				{
					returnValue += "\n\t  Endpoint " + j;
					returnValue += "\n\t\tAddress: " + usbInterface.getEndpoint(j).getAddress();
					returnValue += "\n\t\tAttributes: " + usbInterface.getEndpoint(j).getAttributes();
					returnValue += "\n\t\tDirection: " + usbInterface.getEndpoint(j).getDirection();
					returnValue += "\n\t\tNumber: " + usbInterface.getEndpoint(j).getEndpointNumber();
					returnValue += "\n\t\tInterval: " + usbInterface.getEndpoint(j).getInterval();
					returnValue += "\n\t\tType: " + usbInterface.getEndpoint(j).getType();
					returnValue += "\n\t\tMax packet size: " + usbInterface.getEndpoint(j).getMaxPacketSize();
				}
			}
		}

		return returnValue;
	}

	public static int toInt(byte b) {
		return (int) b & 0xFF;
	}

	public static byte toByte(int c) {
		return (byte) (c <= 0x7f ? c : ((c % 0x80) - 0x80));
	}

	public static String bytes2HexString(byte[] b)
	{
		String stmp="";
		StringBuilder sb = new StringBuilder("");
		for (int n=0;n<b.length;n++)
		{
			stmp = Integer.toHexString(b[n] & 0xFF);
			sb.append((stmp.length()==1)? "0"+stmp : stmp);
			sb.append(" ");
		}
		return sb.toString().toUpperCase().trim();
	}
}
