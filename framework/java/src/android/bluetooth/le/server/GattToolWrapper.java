/*
 * Copyright (c) 2012 Naranjo Manuel Francisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth.le.server;

import android.bluetooth.le.server.gatttool.Response;
import android.util.Log;

import com.broadcom.bt.le.api.BleGattID;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

interface WorkerHandler {
    void EOF();
    void lineReceived(String t);
}

interface internalGattToolListener {
    void commandCompleted();
    void connected();
    void disconnected();    
}

public class GattToolWrapper implements WorkerHandler, internalGattToolListener {
    private Process mProcess;
    private DataInputStream mInput;
    private DataOutputStream mOutput;
    private Worker mWorker;
    private GattToolListener mListener;
    private boolean mBusy;
    
    private enum STATUS {
        IDLE,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        PRIMARY_DISCOVERY,
        PRIMARY_DISCOVERY_UUID,
        CHARACTERISTICS_DISCOVERY,
        CHARACTERISTICS_DESCRIPTOR_DISCOVERY,
        CHARACTERISTICS_READ_UUID,
        CHARACTERISTICS_READ_HANDLE,
        CHARACTERISTIC_WRITE_REQ,
        CHARACTERISTIC_WRITE_CMD,
        SET_SEC_LEVEL,
        SET_MTU,
    };
    
    private STATUS mStatus = STATUS.IDLE;
    
    private static Set<GattToolWrapper> workerPool;
    
    public static void initWorkerPool(int size) throws IOException{
        if (workerPool != null)
        {
            Log.e(TAG, "tried to initialize worker pool again");
            return;
        }
        
        workerPool = new HashSet<GattToolWrapper>(size);
        for (int i = 0; i < size; i++)
            workerPool.add(new GattToolWrapper());
    }
    
    public static GattToolWrapper getWorker(){
        Iterator<GattToolWrapper> it = workerPool.iterator();
        while (it.hasNext()){
            GattToolWrapper w = it.next();
            synchronized (w){
                if (w.mBusy == false){
                    w.mBusy = true;
                    return w;
                }
            }
        }
        Log.e(TAG, "no more workers available");
        return null;
    }
    
    public void releaseWorker(){
        synchronized (this){
            sendCommand("disconnect");
            mBusy = false;
        }
    }
    
    public void commandCompleted(){
        synchronized (this) {
            if (mStatus == STATUS.IDLE) {
                Log.e(TAG, "command complte with status == IDLE");
                return;
            }

            if (mStatus == STATUS.CONNECTING || mStatus == STATUS.DISCONNECTING) {
                Log.v(TAG, "command completed with [dis]connecting status");
                return;
            }

            Log.v(TAG, "command completed");

            mStatus = STATUS.CONNECTED;
        }
    }
    
    public void connected(){
        synchronized (this) {
            this.mStatus = STATUS.CONNECTED;
        }
            
    }
    
    public void disconnected(){
        synchronized (this) {
            this.mStatus = STATUS.IDLE;
        }
    }
    
    private boolean sendCommand(String i){
        if (!mBusy){
            Log.e(TAG, "send command on non busy worker");
            return false;
        }
        
        Log.v(TAG,  "sendCommand " + i);
        try {
            mOutput.writeChars(i+"\n");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "something went wrong", e);
            return false;
        }
    }
    
    public boolean isBusy(){
        return mBusy;
    }
    
    public synchronized boolean connect(String address){
        return this.connect(address, "");
    }
    
    public synchronized boolean connect(String address, String address_type){
        if (mStatus != STATUS.IDLE){
            Log.e(TAG, "connect on connected worker");
            return false;
        }
        
        mStatus = STATUS.CONNECTING;
        return sendCommand("connect " + address + " " + address_type);
    }
    
    public synchronized boolean disconnect(){
        if (mStatus != STATUS.CONNECTED){
            if (mStatus != STATUS.IDLE)
                Log.e(TAG, "not connected");
            else {
                Log.v(TAG, "some command is running can't disconnect");
            }
            return false;
        }
        
        mStatus = STATUS.DISCONNECTING;
        return sendCommand("disconnect");
    }
    
    public synchronized boolean primaryDiscovery(){
        if (mStatus == STATUS.IDLE){
            Log.e(TAG, "not connected");
            return false;
        }
        
        if (mStatus != STATUS.CONNECTED){
            Log.v(TAG, "a command is running can't start primary discovery");
            return false;
        }
        
        mStatus = STATUS.PRIMARY_DISCOVERY;
        return sendCommand("primary");
    }
    
    public synchronized boolean primaryDiscoveryByUUID(BleGattID uuid){
        if (mStatus == STATUS.IDLE){
            Log.e(TAG, "not connected");
            return false;
        }
        
        if (mStatus != STATUS.CONNECTED){
            Log.v(TAG, "a command is running can't start primary by uuid discovery");
            return false;
        }
        
        mStatus = STATUS.PRIMARY_DISCOVERY_UUID;
                
        return sendCommand("primary " + uuid.toString());
    }

    private static final String TOOL = "/system/bin/gatttool-btle";
    public static String TAG = "GATTTOOL";

    public GattToolWrapper() throws IOException {
        mProcess = new ProcessBuilder(TOOL + " -I").redirectErrorStream(true).start();
        mInput = new DataInputStream(mProcess.getInputStream());
        mOutput = new DataOutputStream(mProcess.getOutputStream());
        mWorker = new Worker(mInput, this);
        mBusy = false;
    }
    
    public void setListener(GattToolListener l){
        this.mListener = l;
    }

    protected class Worker extends Thread
    {
        private BufferedReader in;
        private WorkerHandler mHandler;

        private Worker(InputStream in, WorkerHandler h) {
            this.in = new BufferedReader(new InputStreamReader(in));
            this.mHandler = h;
        }

        public void run()
        {
            try {
                Log.v(TAG, "starting worker");

                while (true) {
                    String line = in.readLine();
                    if (line == null) {
                        Log.v(TAG, "EOF");
                        mHandler.EOF();
                        break;
                    }
                    if ("".equals(line))
                        continue;
                    Log.v(TAG, "got line: " + line);
                    mHandler.lineReceived(line);
                }
            } catch (Exception e) {
                Log.e(TAG, "something failed", e);
            }
        }
    }

    @Override
    public void EOF() {
        try {
            int exitCode = mProcess.exitValue();
            Log.v(TAG, "Process stdin closed with retValue: " + exitCode);
            mListener.processExit(exitCode);
        } catch (IllegalThreadStateException e) {
            Log.v(TAG, "Process stind closed but process is still running");
            mListener.processStdinClosed();
            this.mProcess.destroy();
        }
    }

    private static final Pattern PROMPT = Pattern.compile(
            "\\[([^\\]]{3})\\]\\[([0-9A-F\\:\\s]{17})\\]\\[(\\S{2})\\]");
    private static final Pattern RESULT = Pattern.compile(
            "([A-Z\\-]*):\\s*([0-9A-F\\:]{17})\\s*(.*)");
    private static final Pattern ERROR = Pattern.compile(
            "ERROR\\((\\d*),(\\d*)\\):.*");

    private static final String[] RESULTS = new String[]{
        "NOTIFICATION",
        "INDICATION",
        "CONNECTED",
        "DISCONNECTED",
        "PRIMARY-ALL",
        "PRIMARY-ALL-END",
        "PRIMARY-UUID",
        "PRIMARY-UUID-END",
        "CHAR",
        "CHAR-END",
        "CHAR-DESC",
        "CHAR-DESC-END",
        "CHAR-VAL-DESC",
        "CHAR-VAL-DESC-END",
        "CHAR-READ-UUID",
        "CHAR-READ-UUID-END",
        "CHAR-WRITE",
        "SEC-LEVEL",
        "MTU",
    };

    private static final Vector<String> END_COMMAND_RESULTS = new Vector<String>();
    
    static {
        END_COMMAND_RESULTS.add("PRIMARY-ALL-END");
        END_COMMAND_RESULTS.add("PRIMARY-UUID-END");
        END_COMMAND_RESULTS.add("CHAR-END");
        END_COMMAND_RESULTS.add("CHAR-VAL-DESC-END");
        END_COMMAND_RESULTS.add("CHAR-READ-UUID-END");
        END_COMMAND_RESULTS.add("CHAR-WRITE");
        END_COMMAND_RESULTS.add("SEC-LEVEL");
        END_COMMAND_RESULTS.add("MTU");   
    }

    
    private String mLastAddress = null;

    @Override
    public void lineReceived(String line) {
        Log.v(TAG, "lineReceived " + line);
        Matcher m;
        m = PROMPT.matcher(line);
        if (m != null) {
            String state, address, type;
            state = m.group(1);
            address = m.group(2);
            type = m.group(3);
            Log.i(TAG, "found prompt " + state + ", " + address + ", " + type);
            mLastAddress = address;
            synchronized (this){
                if (state=="CON" && mStatus == STATUS.IDLE)
                    this.mStatus = STATUS.CONNECTED;
                if (state.trim().length()==0 && mStatus != STATUS.IDLE)
                    this.mStatus = STATUS.IDLE;
            }
            return;
        }
        m = RESULT.matcher(line);
        
        if (m != null){
            String command, address, argument;
            command = m.group(1);
            address = m.group(2);
            argument = m.group(3);
            
            if (mListener == null) {
                Log.v(TAG, "parsed a command, but no one is listening, dropping");
                return;
            }
            
            if (!address.equals(mLastAddress)) {
                Log.e(TAG, "address changed since we got last prompt");
                mListener.shellError(SHELL_ERRORS.ADDRESS_CHANGED);
                return;
            }
            
            if (Response.processLine(this.mListener, command, address, argument)){
                if (END_COMMAND_RESULTS.contains(command))
                {
                    synchronized (this){
                        mStatus = STATUS.CONNECTED;
                    }
                }
                return;
            }
        }
        m = ERROR.matcher(line);
    }
    
    public enum SHELL_ERRORS {
        ADDRESS_CHANGED
    };
    
    public interface GattToolListener {
        public void onNotification(String addr, int handle, byte[] value);
        public void onIndication(String addr, int handle, byte[] value);
        public void connected(String addr, int status);
        public void disconnected(String addr);
        public void primaryAll(String addr, int start, int end, BleGattID uuid);
        public void primaryAllEnd(String addr, int status);
        public void primaryUuid(String addr, int start, int end);
        public void primaryUuidEnd(String addr, int status);
        public void characteristic(String addr, int handle, short properties, int value_handle,
                BleGattID uuid);
        public void characteristicEnd(String addr, int status);
        public void characteristicDescriptor(String addr, int handle, BleGattID uuid);
        public void characteristicDescriptorEnd(String addr, int status);
        public void gotValueByHandle(String addr, byte[] value, int status);
        public void gotValueByUuid(String addr, BleGattID uuid, byte[] value);
        public void gotValueByUuidEnd(String addr, int status);
        public void gotWriteResult(String addr, int status);
        public void gotSecurityLevelResult(String addr, int status);
        public void gotMtuResult(String addr, int status);
        
        public void processExit(int retcode);
        public void processStdinClosed();
        public void shellError(SHELL_ERRORS e);
    }

}
