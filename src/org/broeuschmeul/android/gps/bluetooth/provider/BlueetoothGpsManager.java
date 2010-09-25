/*
 * Copyright (C) 2010 Herbert von Broeuschmeul
 * Copyright (C) 2010 BluetoothGPS4Droid Project
 * 
 * This file is part of BluetoothGPS4Droid.
 *
 * BluetoothGPS4Droid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * BluetoothGPS4Droid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with BluetoothGPS4Droid. If not, see <http://www.gnu.org/licenses/>.
 */

package org.broeuschmeul.android.gps.bluetooth.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.broeuschmeul.android.gps.nmea.util.NmeaParser;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.location.LocationManager;
import android.location.GpsStatus.NmeaListener;
import android.os.SystemClock;
import android.util.Log;

public class BlueetoothGpsManager {

	private class ConnectedGps extends Thread {
		    private final InputStream in;
		    private final OutputStream out;
		    private final PrintStream out2;
		    private boolean ready = false;
	
		    public ConnectedGps(BluetoothSocket socket) {
		        InputStream tmpIn = null;
		        OutputStream tmpOut = null;
		        PrintStream tmpOut2 = null;
		        try {
		        	tmpIn = socket.getInputStream();
		        	tmpOut = socket.getOutputStream();
		        	if (tmpOut != null){
		        		tmpOut2 = new PrintStream(tmpOut, false, "US-ASCII");
		        	}
		        } catch (IOException e) {
		        	Log.e("BT test", "error while getting socket streams", e);
		        }	
		        in = tmpIn;
		        out = tmpOut;
		        out2 = tmpOut2;
		    }
	
		    public void run() {
		        try {
		        	BufferedReader reader = new BufferedReader(new InputStreamReader(in,"US-ASCII"));
		        	String s;
		        	long now = SystemClock.uptimeMillis();
		        	long lastRead = now;
//					while((enabled && (s = reader.readLine()) != null)){
					while((enabled) && (now < lastRead+5000 )){
						if (reader.ready()){
							s = reader.readLine();
						Log.e("BT test", "data: "+System.currentTimeMillis()+" "+s + "xxx");
						notifyNmeaSentence(s+"\r\n");
						ready = true;
						lastRead = SystemClock.uptimeMillis();
//						parser.parseNmeaSentence(s);
//	//					writer.println(s);
//						addNMEAString(s);
//						nmeaSentenceHandler.ob
						} else {
							Log.e("BT test", "data: not ready "+System.currentTimeMillis());
							SystemClock.sleep(500);
						}
						now = SystemClock.uptimeMillis();
					}
				} catch (IOException e) {
		        	Log.e("BT test", "error while getting data", e);
		        	setMockLocationProviderOutOfService();
				} finally {
					// remove because we want to retry...
//					disable();
				}
		    }
		}

	private Service callingService;
	private BluetoothDevice gpsDevice;
	private BluetoothSocket gpsSocket;
	private String gpsDeviceAddress;
	private NmeaParser parser = new NmeaParser(10f);
	private boolean enabled = false;
	private ExecutorService notificationPool;
	private ScheduledExecutorService connectionAndReadingPool;
	private List<NmeaListener> nmeaListeners = Collections.synchronizedList(new LinkedList<NmeaListener>()); 
	private LocationManager locationManager;
//	private boolean mockGpsEnabled = true;
//	private String mockLocationProvider = LocationManager.GPS_PROVIDER;
	private ConnectedGps connectedGps;

//	private Handler nmeaSentenceHandler = new Handler();

	
	/**
	 * @return true if the bluetooth GPS is enabled
	 */
	public synchronized boolean isEnabled() {
		return enabled;
	}

	public BlueetoothGpsManager(Service callingService, String deviceAddress) {
		this.gpsDeviceAddress = deviceAddress;
		this.callingService = callingService;
		locationManager = (LocationManager)callingService.getSystemService(Context.LOCATION_SERVICE);
		parser.setLocationManager(locationManager);	
	}

	public synchronized boolean enable() {
		if (! enabled){
			final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
	        if (bluetoothAdapter == null) {
	            // Device does not support Bluetooth
	        	Log.e("BT test", "Device does not support Bluetooth");
	        } else if (!bluetoothAdapter.isEnabled()) {
//	        	    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
//	        	    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
	        	Log.e("BT test", "Bluetooth is not enabled");
	        } else {
	    		final BluetoothDevice gpsDevice = bluetoothAdapter.getRemoteDevice(gpsDeviceAddress);
	    		if (gpsDevice == null){
	    			Log.e("BT test", "GPS device not found");       	    	
	    		} else {
	    			Log.e("BT test", "current device: "+gpsDevice.getName() + " -- " + gpsDevice.getAddress());
	    			try {
	    				gpsSocket = gpsDevice.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
	    			} catch (IOException e) {
	    				Log.e("BT test", "Error during connection", e);
	    			}
	    			if (gpsSocket == null){
	    				Log.e("BT test", "Error while establishing connection: no socket");
	    			} else {
	    				Runnable connectThread = new Runnable() {							
	    					private int connectionTry=0;
							@Override
							public void run() {
								try {
									connectionTry++;
									Log.e("BT test", "current device: "+gpsDevice.getName() + " -- " + gpsDevice.getAddress());
									try {
										if (gpsSocket != null){
											Log.e("BT test", "trying to close old socket");
											gpsSocket.close();
										}
									} catch (IOException e) {
										Log.e("BT test", "Error during disconnection", e);
									}
									try {
										gpsSocket = gpsDevice.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
									} catch (IOException e) {
										Log.e("BT test", "Error during connection", e);
									}
									if (gpsSocket == null){
										Log.e("BT test", "Error while establishing connection: no socket");
									} else {
								// Cancel discovery because it will slow down the connection
								bluetoothAdapter.cancelDiscovery();
										// we increment the number of connection try
						            // Connect the device through the socket. This will block
						            // until it succeeds or throws an exception
						        	gpsSocket.connect();
										// connection obtained so reset the number of connection try
										connectionTry=0;
										connectedGps = new ConnectedGps(gpsSocket);
										connectionAndReadingPool.execute(connectedGps);
//				    				connectedGps.start();
//				    				String command = callingService.getString(R.string.sirf_gll_on);
//				    				String sentence = String.format((Locale)null,"$%s*%X\r\n", command, parser.computeChecksum(command)); 
//				    				String command1 = callingService.getString(R.string.sirf_gll_off);
//				    				String sentence1 = String.format((Locale)null,"$%s*%X\r\n", command1, parser.computeChecksum(command1)); 
//				    				String command2 = callingService.getString(R.string.sirf_vtg_off);
//				    				String sentence2 = String.format((Locale)null,"$%s*%X\r\n", command2, parser.computeChecksum(command2)); 
//				    				try {
//										Thread.sleep(5000);
//									} catch (InterruptedException e) {
//										// TODO Auto-generated catch block
//										e.printStackTrace();
//									}
//				    				Log.e("BT test", "sending NMEA sentence: "+"$PSRF105,1*3E\r\n");
//				    				connectedGps.write("$PSRF105,1*3E\r\n");	    								    				
//				    				Log.e("BT test", "sending NMEA sentence: "+sentence1);
//				    				connectedGps.write(sentence1);	    								    				
//				    				Log.e("BT test", "sending NMEA sentence: "+sentence2);
//				    				connectedGps.write(sentence2);	    								    				
									}
						        } catch (IOException connectException) {
						            // Unable to connect; close everything and get out
						        	Log.e("BT test", "error while connecting to socket", connectException);
//						        		callingService.stopSelf();
						        } finally {
						        	// if bluetooth has bean disabled or
						        	// if two much tries consider that we are enable to connect. So close everything and get out
						        	if ((!bluetoothAdapter.isEnabled()) || (connectionTry > 5 )){
									disable();
						        }
							}
							}
						};
						this.enabled = true;
						notificationPool = Executors.newSingleThreadExecutor();
						connectionAndReadingPool = Executors.newSingleThreadScheduledExecutor();
						connectionAndReadingPool.scheduleWithFixedDelay(connectThread, 100, 60000, TimeUnit.MILLISECONDS);
//						enableMockLocationProvider(LocationManager.GPS_PROVIDER);
	    			}
	    		}
	        }
		}
		return this.enabled;
	}
	
	public synchronized void disable() {
		if (enabled){
			enabled = false;
			if (gpsSocket != null){
		    	try {
		    		gpsSocket.close();
		    	} catch (IOException closeException) {
		    		Log.e("BT test", "error while closing socket", closeException);
		    	}
			}
			nmeaListeners.clear();
			disableMockLocationProvider();
			notificationPool.shutdown();
////			locationManager.setTestProviderEnabled(mockLocationProvider, mockGpsEnabled);
//			LocationProvider prov = locationManager.getProvider(mockLocationProvider);
//			Log.e("BT test", "Mock power: "+prov.getPowerRequirement()+" "+prov.getAccuracy()+" "+locationManager.isProviderEnabled(mockLocationProvider));
//			locationManager.clearTestProviderEnabled(mockLocationProvider);
//			prov = locationManager.getProvider(mockLocationProvider);
//			Log.e("BT test", "Mock power: "+prov.getPowerRequirement()+" "+prov.getAccuracy()+" "+locationManager.isProviderEnabled(mockLocationProvider));
//			locationManager.clearTestProviderStatus(mockLocationProvider);
//			locationManager.removeTestProvider(mockLocationProvider);
//			prov = locationManager.getProvider(mockLocationProvider);
//			Log.e("BT test", "Mock power: "+prov.getPowerRequirement()+" "+prov.getAccuracy()+" "+locationManager.isProviderEnabled(mockLocationProvider));
//			Log.e("BT test", "removed mock GPS");
			
			callingService.stopSelf();
		}
	}

	
	public void enableMockLocationProvider(String gpsName){
		if (parser != null){
			parser.enableMockLocationProvider(gpsName);
		}
	}
	
	public void disableMockLocationProvider(){
		if (parser != null){
			parser.disableMockLocationProvider();
		}
	}

	/**
	 * @return the mockGpsEnabled
	 */
	public boolean isMockGpsEnabled() {
		boolean mockGpsEnabled = false;
		if (parser != null){
			mockGpsEnabled = parser.isMockGpsEnabled();
		}
		return mockGpsEnabled;
	}
	/**
	 * @return the mockLocationProvider
	 */
	public String getMockLocationProvider() {
		String  mockLocationProvider = null;
		if (parser != null){
			mockLocationProvider = parser.getMockLocationProvider();
		}
		return mockLocationProvider;
	}
	
	private void setMockLocationProviderOutOfService(){
		if (parser != null){
			parser.setMockLocationProviderOutOfService();
		}
	}

	public boolean addNmeaListener(NmeaListener listener){
		if (!nmeaListeners.contains(listener)){
			nmeaListeners.add(listener);
		}
		return true;
	}
	
	public void removeNmeaListener(NmeaListener listener){
			nmeaListeners.remove(listener);
	}
	
	private void notifyNmeaSentence(final String nmeaSentence){
		if (enabled){
			final String recognizedSentence = parser.parseNmeaSentence(nmeaSentence);
			final long timestamp = System.currentTimeMillis();
			if (recognizedSentence != null){
				Log.e("BT test", "NMEA : "+timestamp+" "+recognizedSentence);
			synchronized(nmeaListeners) {
				for(final NmeaListener listener : nmeaListeners){
					notificationPool.execute(new Runnable(){
						@Override
						public void run() {
							listener.onNmeaReceived(timestamp, nmeaSentence);
						}					 
					});
				}
			}
		}
		}
	}	
}
