package BluetoothTesting;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/*
Connection is initiated here.
We set up a secure and insecure connection, where we could use two different UUIDs
You could also use a method to generate a UUID from the device, but I used the google route

The majority of the bluetooth socket connections are taken from:
http://developer.android.com/guide/topics/connectivity/bluetooth.html

This helped initiate the connection between a foreign device and the android phone
It would handle if Bluetooth is off, if so it would tell the user
Otherwise, pair the devices and create a Input and Output stream for data.

Once the I/O streams are established, keep checking and place in a byte array to use for later.

*/



/**
 * Created by srMBP on 2016-03-19.
 */
public class ConnectionAid {
    private static final String NAME_SECURE = "BluetoothChatSecure";
    private static final String NAME_INSECURE = "BluetoothChatInsecure";

    // Unique UUID for the HC-06 Or RN-42 modules, this was commonly found on many Stack Overflow questions on why the App wouldn't connect
    private static final UUID MY_UUID_SECURE = UUID
            .fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID MY_UUID_INSECURE = UUID
            .fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Member fields to be used later
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler handler;
    private AcceptThread secureAcceptThread;
    private AcceptThread insecureAcceptThread;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private int state;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;
    public static final int STATE_LISTEN = 1; // listening connection
    public static final int STATE_CONNECTING = 2; // initiate outgoing
    // connection
    public static final int STATE_CONNECTED = 3; // connected to remote device

    public ConnectionAid(Context context, Handler handler) {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        state = STATE_NONE;

        this.handler = handler;
    }

    // Set the current state of the chat connection
    private synchronized void setState(int state) {
        this.state = state;

        handler.obtainMessage(MainActivity.MESSAGE_STATE_CHANGE, state, -1)
                .sendToTarget();
    }

    // get current connection state
    public synchronized int getState() {
        return state;
    }

    // start service
    public synchronized void start() {
        // Cancel any thread
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel any running thresd
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        setState(STATE_LISTEN);

        // Start the thread to listen on a BluetoothServerSocket
        if (secureAcceptThread == null) {
            secureAcceptThread = new AcceptThread(true);
            secureAcceptThread.start();
        }
        if (insecureAcceptThread == null) {
            insecureAcceptThread = new AcceptThread(false);
            insecureAcceptThread.start();
        }
    }

    // initiate connection to remote device
    public synchronized void connect(BluetoothDevice device, boolean secure) {
        // Cancel any thread
        if (state == STATE_CONNECTING) {
            if (connectThread != null) {
                connectThread.cancel();
                connectThread = null;
            }
        }

        // Cancel running thread
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        // Start the thread to connect with the given device
        connectThread = new ConnectThread(device, secure);
        connectThread.start();
        setState(STATE_CONNECTING);
    }

    // manage Bluetooth connection
    public synchronized void connected(BluetoothSocket socket,
                                       BluetoothDevice device, final String socketType) {
        // Cancel the thread
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel running thread
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (secureAcceptThread != null) {
            secureAcceptThread.cancel();
            secureAcceptThread = null;
        }
        if (insecureAcceptThread != null) {
            insecureAcceptThread.cancel();
            insecureAcceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        connectedThread = new ConnectedThread(socket, socketType);
        connectedThread.start();

        // Send the name of the connected device back to the UI Activity
        Message msg = handler.obtainMessage(MainActivity.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(MainActivity.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        handler.sendMessage(msg);

        setState(STATE_CONNECTED);
    }

    // stop all threads
    public synchronized void stop() {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (secureAcceptThread != null) {
            secureAcceptThread.cancel();
            secureAcceptThread = null;
        }

        if (insecureAcceptThread != null) {
            insecureAcceptThread.cancel();
            insecureAcceptThread = null;
        }
        setState(STATE_NONE);
    }

    public void write(byte[] out) {
        ConnectedThread r;
        synchronized (this) {
            if (state != STATE_CONNECTED)
                return;
            r = connectedThread;
        }
        r.write(out);
    }
/*
If a connection fails, let the user know with a pop up message
*/

    private void connectionFailed() {
        Message msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(MainActivity.TOAST, "Unable to connect device");
        msg.setData(bundle);
        handler.sendMessage(msg);

        // Start the service over to restart listening mode
        ConnectionAid.this.start();
    }

    /*
If a connection is lost, Let the user know
*/
    private void connectionLost() {
        Message msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(MainActivity.TOAST, "Device connection was lost");
        msg.setData(bundle);
        handler.sendMessage(msg);

        // Start the service over to restart listening mode
        ConnectionAid.this.start();
    }

    // runs while listening for incoming connections
    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;
        private String socketType;

        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            socketType = secure ? "Secure" : "Insecure";

            try {
                if (secure) {
                    tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                            NAME_SECURE, MY_UUID_SECURE);
                } else {
                    tmp = bluetoothAdapter
                            .listenUsingInsecureRfcommWithServiceRecord(
                                    NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
            }
            serverSocket = tmp;
        }


        /*
        If there is another RFCOMM socket device ready to connect, than accept
        */
        public void run() {
            setName("AcceptThread" + socketType);

            BluetoothSocket socket = null;

            while (state != STATE_CONNECTED) {
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (ConnectionAid.this) {
                        switch (state) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                // start the connected thread.
                                connected(socket, socket.getRemoteDevice(),
                                        socketType);
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                // Either not ready or already connected. Terminate
                                // new socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                }
                                break;
                        }
                    }
                }
            }
        }

        public void cancel() {
            try {
                serverSocket.close();
            } catch (IOException e) {
            }
        }
    }

    // runs while attempting to make an outgoing connection
    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;
        private String socketType;

        public ConnectThread(BluetoothDevice device, boolean secure) {
            this.device = device;
            BluetoothSocket tmp = null;
            socketType = secure ? "Secure" : "Insecure";

            try {
                if (secure) {
                    tmp = device
                            .createRfcommSocketToServiceRecord(MY_UUID_SECURE);
                } else {
                    tmp = device
                            .createInsecureRfcommSocketToServiceRecord(MY_UUID_INSECURE);
                }
            } catch (IOException e) {
            }
            socket = tmp;
        }

        public void run() {
            setName("ConnectThread" + socketType);

            // Always cancel discovery because it will slow down a connection
            bluetoothAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                socket.connect();
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException e2) {
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (ConnectionAid.this) {
                connectThread = null;
            }

            // Start the connected thread
            connected(socket, device, socketType);
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
            }
        }
    }

    // runs during a connection with a remote device
    private class ConnectedThread extends Thread {
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            this.bluetoothSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
            }

            inputStream = tmpIn;
            outputStream = tmpOut;
        }


        /*
        This Writes and reads from the input stream and allocate the appropriate size according to the stream
        This Allocates these I/O streams to the Message read or write handlers that can be accessed in MainActivity Java Class
        */

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            // Keep listening to the InputStream
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = inputStream.read(buffer);

                    // Send the obtained bytes to the UI Activity
                    handler.obtainMessage(MainActivity.MESSAGE_READ, bytes, -1,
                            buffer).sendToTarget();
                } catch (IOException e) {
                    connectionLost();
                    // Start the service over to restart listening mode
                    ConnectionAid.this.start();
                    break;
                }
            }
        }

        // write to OutputStream
        public void write(byte[] buffer) {
            try {
                outputStream.write(buffer);
                handler.obtainMessage(MainActivity.MESSAGE_WRITE, -1, -1,
                        buffer).sendToTarget();
            } catch (IOException e) {
            }
        }

        public void cancel() {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
            }
        }
    }

/*
Testing ways to output message in different HEX Format, so without skipping every line, we just throw it all together

    public static String outHex(String hex) {
        StringBuilder sb = new StringBuilder();
        int len = hex.length();
        try {
            for (int i = 0; i < len; i += 2) {
                sb.append("0x").append(hex.substring(i, i + 2)).append(" ");
            }
        } catch (NumberFormatException e) {
            ("printHex NumberFormatException: " + e.getMessage());

        } catch (StringIndexOutOfBoundsException e) {
            ("printHex StringIndexOutOfBoundsException: " + e.getMessage());
        }
        return sb.toString();
    }
    // ============================================================================



    public static byte[] goingHex(String hex) {
        int len = hex.length();
        byte[] result = new byte[len];
        try {
            int index = 0;
            for (int i = 0; i < len; i += 2) {
                result[index] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
                index++;
            }
        } catch (NumberFormatException e) {
            ("toHex NumberFormatException: " + e.getMessage());

        } catch (StringIndexOutOfBoundsException e) {
            ("toHex StringIndexOutOfBoundsException: " + e.getMessage());
        }
        return result;
    }
    */
}
