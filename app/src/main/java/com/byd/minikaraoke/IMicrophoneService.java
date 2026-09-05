package com.byd.minikaraoke;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import com.byd.minikaraoke.IBufferCallback;
import com.byd.minikaraoke.IConnectionStateListener;
import com.byd.minikaraoke.IErrorListener;
import com.byd.minikaraoke.IKaraokeModeListener;
import com.byd.minikaraoke.ISettingListener;
import com.byd.minikaraoke.api.IMessageCallback;
import com.byd.minikaraoke.api.IMessageCallback2;
import com.byd.minikaraoke.api.IStartKaraokeModeCallback;
import com.byd.minikaraoke.api.MicrophoneDevice;
import java.util.List;

/* loaded from: classes.dex */
public interface IMicrophoneService extends IInterface {

    /* loaded from: classes.dex */
    public static class Default implements IMicrophoneService {
        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public boolean decreaseMicVolume() throws RemoteException {
            return false;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public String getAudioManagerParameters(String str) throws RemoteException {
            return null;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public List<MicrophoneDevice> getDeviceList() throws RemoteException {
            return null;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public int getEffect() throws RemoteException {
            return 0;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public int getKaraokeMode() throws RemoteException {
            return 0;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public Bundle getMessage(int i, int i2, int i3, Bundle bundle) throws RemoteException {
            return null;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public int getMessageAsync(int i, int i2, int i3, Bundle bundle, IMessageCallback iMessageCallback) throws RemoteException {
            return 0;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public boolean getMicConnectionState() throws RemoteException {
            return false;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public int getMicVolume() throws RemoteException {
            return 0;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public int getReverberation() throws RemoteException {
            return 0;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public boolean increaseMicVolume() throws RemoteException {
            return false;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public boolean isBuiltInMicKaraokeModeSupport() throws RemoteException {
            return false;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void registerBufferCallback(IBufferCallback iBufferCallback) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void registerBuiltinMicKaraokeClient(String str, int i, IBinder iBinder) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void registerErrorListener(IErrorListener iErrorListener) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void registerKaraokeModeListener(IKaraokeModeListener iKaraokeModeListener) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void registerMessageCallback(int i, String str, Bundle bundle, IMessageCallback2 iMessageCallback2, IBinder iBinder) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void registerMicrophoneConnectionStateListener(IConnectionStateListener iConnectionStateListener) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void registerSettingListener(ISettingListener iSettingListener) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public int sendBuffer(byte[] bArr) throws RemoteException {
            return 0;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void sendBuiltInMicKaraokeModeAction(int i, int i2, int i3, Bundle bundle, IStartKaraokeModeCallback iStartKaraokeModeCallback) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public boolean setAudioManagerParameters(String str) throws RemoteException {
            return false;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void setBufferCallback(IBufferCallback iBufferCallback) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public boolean setEffect(int i) throws RemoteException {
            return false;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public int setMessage(int i, int i2, int i3, Bundle bundle) throws RemoteException {
            return 0;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public int setMessageAsync(int i, int i2, int i3, Bundle bundle, IMessageCallback iMessageCallback) throws RemoteException {
            return 0;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public boolean setMicVolume(int i) throws RemoteException {
            return false;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public boolean setMicVolumeWithShow(int i, boolean z) throws RemoteException {
            return false;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public boolean setReverberation(int i) throws RemoteException {
            return false;
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void startBuiltInMicKaraokeMode(int i) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void startBuiltInMicKaraokeModeWithCallback(int i, IStartKaraokeModeCallback iStartKaraokeModeCallback) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void stopBuiltInMicKaraokeMode(int i) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void unregisterBufferCallback(IBufferCallback iBufferCallback) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void unregisterBuiltinMicKaraokeClient(IBinder iBinder) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void unregisterErrorListener(IErrorListener iErrorListener) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void unregisterKaraokeModeListener(IKaraokeModeListener iKaraokeModeListener) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void unregisterMessageCallback(int i, String str, Bundle bundle, IMessageCallback2 iMessageCallback2, IBinder iBinder) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void unregisterMicrophoneConnectionStateListener(IConnectionStateListener iConnectionStateListener) throws RemoteException {
        }

        @Override // com.byd.minikaraoke.IMicrophoneService
        public void unregisterSettingListener(ISettingListener iSettingListener) throws RemoteException {
        }
    }

    boolean decreaseMicVolume() throws RemoteException;

    String getAudioManagerParameters(String str) throws RemoteException;

    List<MicrophoneDevice> getDeviceList() throws RemoteException;

    int getEffect() throws RemoteException;

    int getKaraokeMode() throws RemoteException;

    Bundle getMessage(int i, int i2, int i3, Bundle bundle) throws RemoteException;

    int getMessageAsync(int i, int i2, int i3, Bundle bundle, IMessageCallback iMessageCallback) throws RemoteException;

    boolean getMicConnectionState() throws RemoteException;

    int getMicVolume() throws RemoteException;

    int getReverberation() throws RemoteException;

    boolean increaseMicVolume() throws RemoteException;

    boolean isBuiltInMicKaraokeModeSupport() throws RemoteException;

    void registerBufferCallback(IBufferCallback iBufferCallback) throws RemoteException;

    void registerBuiltinMicKaraokeClient(String str, int i, IBinder iBinder) throws RemoteException;

    void registerErrorListener(IErrorListener iErrorListener) throws RemoteException;

    void registerKaraokeModeListener(IKaraokeModeListener iKaraokeModeListener) throws RemoteException;

    void registerMessageCallback(int i, String str, Bundle bundle, IMessageCallback2 iMessageCallback2, IBinder iBinder) throws RemoteException;

    void registerMicrophoneConnectionStateListener(IConnectionStateListener iConnectionStateListener) throws RemoteException;

    void registerSettingListener(ISettingListener iSettingListener) throws RemoteException;

    int sendBuffer(byte[] bArr) throws RemoteException;

    void sendBuiltInMicKaraokeModeAction(int i, int i2, int i3, Bundle bundle, IStartKaraokeModeCallback iStartKaraokeModeCallback) throws RemoteException;

    boolean setAudioManagerParameters(String str) throws RemoteException;

    void setBufferCallback(IBufferCallback iBufferCallback) throws RemoteException;

    boolean setEffect(int i) throws RemoteException;

    int setMessage(int i, int i2, int i3, Bundle bundle) throws RemoteException;

    int setMessageAsync(int i, int i2, int i3, Bundle bundle, IMessageCallback iMessageCallback) throws RemoteException;

    boolean setMicVolume(int i) throws RemoteException;

    boolean setMicVolumeWithShow(int i, boolean z) throws RemoteException;

    boolean setReverberation(int i) throws RemoteException;

    void startBuiltInMicKaraokeMode(int i) throws RemoteException;

    void startBuiltInMicKaraokeModeWithCallback(int i, IStartKaraokeModeCallback iStartKaraokeModeCallback) throws RemoteException;

    void stopBuiltInMicKaraokeMode(int i) throws RemoteException;

    void unregisterBufferCallback(IBufferCallback iBufferCallback) throws RemoteException;

    void unregisterBuiltinMicKaraokeClient(IBinder iBinder) throws RemoteException;

    void unregisterErrorListener(IErrorListener iErrorListener) throws RemoteException;

    void unregisterKaraokeModeListener(IKaraokeModeListener iKaraokeModeListener) throws RemoteException;

    void unregisterMessageCallback(int i, String str, Bundle bundle, IMessageCallback2 iMessageCallback2, IBinder iBinder) throws RemoteException;

    void unregisterMicrophoneConnectionStateListener(IConnectionStateListener iConnectionStateListener) throws RemoteException;

    void unregisterSettingListener(ISettingListener iSettingListener) throws RemoteException;

    /* loaded from: classes.dex */
    public static abstract class Stub extends Binder implements IMicrophoneService {
        private static final String DESCRIPTOR = "com.byd.minikaraoke.IMicrophoneService";
        static final int TRANSACTION_decreaseMicVolume = 8;
        static final int TRANSACTION_getAudioManagerParameters = 30;
        static final int TRANSACTION_getDeviceList = 19;
        static final int TRANSACTION_getEffect = 22;
        static final int TRANSACTION_getKaraokeMode = 17;
        static final int TRANSACTION_getMessage = 34;
        static final int TRANSACTION_getMessageAsync = 36;
        static final int TRANSACTION_getMicConnectionState = 11;
        static final int TRANSACTION_getMicVolume = 10;
        static final int TRANSACTION_getReverberation = 20;
        static final int TRANSACTION_increaseMicVolume = 7;
        static final int TRANSACTION_isBuiltInMicKaraokeModeSupport = 18;
        static final int TRANSACTION_registerBufferCallback = 31;
        static final int TRANSACTION_registerBuiltinMicKaraokeClient = 26;
        static final int TRANSACTION_registerErrorListener = 5;
        static final int TRANSACTION_registerKaraokeModeListener = 15;
        static final int TRANSACTION_registerMessageCallback = 38;
        static final int TRANSACTION_registerMicrophoneConnectionStateListener = 1;
        static final int TRANSACTION_registerSettingListener = 24;
        static final int TRANSACTION_sendBuffer = 4;
        static final int TRANSACTION_sendBuiltInMicKaraokeModeAction = 37;
        static final int TRANSACTION_setAudioManagerParameters = 29;
        static final int TRANSACTION_setBufferCallback = 3;
        static final int TRANSACTION_setEffect = 23;
        static final int TRANSACTION_setMessage = 33;
        static final int TRANSACTION_setMessageAsync = 35;
        static final int TRANSACTION_setMicVolume = 9;
        static final int TRANSACTION_setMicVolumeWithShow = 12;
        static final int TRANSACTION_setReverberation = 21;
        static final int TRANSACTION_startBuiltInMicKaraokeMode = 13;
        static final int TRANSACTION_startBuiltInMicKaraokeModeWithCallback = 28;
        static final int TRANSACTION_stopBuiltInMicKaraokeMode = 14;
        static final int TRANSACTION_unregisterBufferCallback = 32;
        static final int TRANSACTION_unregisterBuiltinMicKaraokeClient = 27;
        static final int TRANSACTION_unregisterErrorListener = 6;
        static final int TRANSACTION_unregisterKaraokeModeListener = 16;
        static final int TRANSACTION_unregisterMessageCallback = 39;
        static final int TRANSACTION_unregisterMicrophoneConnectionStateListener = 2;
        static final int TRANSACTION_unregisterSettingListener = 25;

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IMicrophoneService asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface queryLocalInterface = iBinder.queryLocalInterface(DESCRIPTOR);
            if (queryLocalInterface != null && (queryLocalInterface instanceof IMicrophoneService)) {
                return (IMicrophoneService) queryLocalInterface;
            }
            return new Proxy(iBinder);
        }

        @Override // android.os.Binder
        public boolean onTransact(int i, Parcel parcel, Parcel parcel2, int i2) throws RemoteException {
            if (i == 1598968902) {
                parcel2.writeString(DESCRIPTOR);
                return true;
            }
            switch (i) {
                case 1:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerMicrophoneConnectionStateListener(IConnectionStateListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 2:
                    parcel.enforceInterface(DESCRIPTOR);
                    unregisterMicrophoneConnectionStateListener(IConnectionStateListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 3:
                    parcel.enforceInterface(DESCRIPTOR);
                    setBufferCallback(IBufferCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 4:
                    parcel.enforceInterface(DESCRIPTOR);
                    int sendBuffer = sendBuffer(parcel.createByteArray());
                    parcel2.writeNoException();
                    parcel2.writeInt(sendBuffer);
                    return true;
                case 5:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerErrorListener(IErrorListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 6:
                    parcel.enforceInterface(DESCRIPTOR);
                    unregisterErrorListener(IErrorListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 7:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean increaseMicVolume = increaseMicVolume();
                    parcel2.writeNoException();
                    parcel2.writeInt(increaseMicVolume ? 1 : 0);
                    return true;
                case 8:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean decreaseMicVolume = decreaseMicVolume();
                    parcel2.writeNoException();
                    parcel2.writeInt(decreaseMicVolume ? 1 : 0);
                    return true;
                case 9:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean micVolume = setMicVolume(parcel.readInt());
                    parcel2.writeNoException();
                    parcel2.writeInt(micVolume ? 1 : 0);
                    return true;
                case 10:
                    parcel.enforceInterface(DESCRIPTOR);
                    int micVolume2 = getMicVolume();
                    parcel2.writeNoException();
                    parcel2.writeInt(micVolume2);
                    return true;
                case 11:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean micConnectionState = getMicConnectionState();
                    parcel2.writeNoException();
                    parcel2.writeInt(micConnectionState ? 1 : 0);
                    return true;
                case 12:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean micVolumeWithShow = setMicVolumeWithShow(parcel.readInt(), parcel.readInt() != 0);
                    parcel2.writeNoException();
                    parcel2.writeInt(micVolumeWithShow ? 1 : 0);
                    return true;
                case 13:
                    parcel.enforceInterface(DESCRIPTOR);
                    startBuiltInMicKaraokeMode(parcel.readInt());
                    parcel2.writeNoException();
                    return true;
                case 14:
                    parcel.enforceInterface(DESCRIPTOR);
                    stopBuiltInMicKaraokeMode(parcel.readInt());
                    parcel2.writeNoException();
                    return true;
                case 15:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerKaraokeModeListener(IKaraokeModeListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 16:
                    parcel.enforceInterface(DESCRIPTOR);
                    unregisterKaraokeModeListener(IKaraokeModeListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 17:
                    parcel.enforceInterface(DESCRIPTOR);
                    int karaokeMode = getKaraokeMode();
                    parcel2.writeNoException();
                    parcel2.writeInt(karaokeMode);
                    return true;
                case 18:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean isBuiltInMicKaraokeModeSupport = isBuiltInMicKaraokeModeSupport();
                    parcel2.writeNoException();
                    parcel2.writeInt(isBuiltInMicKaraokeModeSupport ? 1 : 0);
                    return true;
                case 19:
                    parcel.enforceInterface(DESCRIPTOR);
                    List<MicrophoneDevice> deviceList = getDeviceList();
                    parcel2.writeNoException();
                    parcel2.writeTypedList(deviceList);
                    return true;
                case 20:
                    parcel.enforceInterface(DESCRIPTOR);
                    int reverberation = getReverberation();
                    parcel2.writeNoException();
                    parcel2.writeInt(reverberation);
                    return true;
                case 21:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean reverberation2 = setReverberation(parcel.readInt());
                    parcel2.writeNoException();
                    parcel2.writeInt(reverberation2 ? 1 : 0);
                    return true;
                case 22:
                    parcel.enforceInterface(DESCRIPTOR);
                    int effect = getEffect();
                    parcel2.writeNoException();
                    parcel2.writeInt(effect);
                    return true;
                case 23:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean effect2 = setEffect(parcel.readInt());
                    parcel2.writeNoException();
                    parcel2.writeInt(effect2 ? 1 : 0);
                    return true;
                case 24:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerSettingListener(ISettingListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 25:
                    parcel.enforceInterface(DESCRIPTOR);
                    unregisterSettingListener(ISettingListener.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 26:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerBuiltinMicKaraokeClient(parcel.readString(), parcel.readInt(), parcel.readStrongBinder());
                    parcel2.writeNoException();
                    return true;
                case 27:
                    parcel.enforceInterface(DESCRIPTOR);
                    unregisterBuiltinMicKaraokeClient(parcel.readStrongBinder());
                    parcel2.writeNoException();
                    return true;
                case 28:
                    parcel.enforceInterface(DESCRIPTOR);
                    startBuiltInMicKaraokeModeWithCallback(parcel.readInt(), IStartKaraokeModeCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 29:
                    parcel.enforceInterface(DESCRIPTOR);
                    boolean audioManagerParameters = setAudioManagerParameters(parcel.readString());
                    parcel2.writeNoException();
                    parcel2.writeInt(audioManagerParameters ? 1 : 0);
                    return true;
                case 30:
                    parcel.enforceInterface(DESCRIPTOR);
                    String audioManagerParameters2 = getAudioManagerParameters(parcel.readString());
                    parcel2.writeNoException();
                    parcel2.writeString(audioManagerParameters2);
                    return true;
                case 31:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerBufferCallback(IBufferCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 32:
                    parcel.enforceInterface(DESCRIPTOR);
                    unregisterBufferCallback(IBufferCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 33:
                    parcel.enforceInterface(DESCRIPTOR);
                    int message = setMessage(parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt() != 0 ? (Bundle) Bundle.CREATOR.createFromParcel(parcel) : null);
                    parcel2.writeNoException();
                    parcel2.writeInt(message);
                    return true;
                case 34:
                    parcel.enforceInterface(DESCRIPTOR);
                    Bundle message2 = getMessage(parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt() != 0 ? (Bundle) Bundle.CREATOR.createFromParcel(parcel) : null);
                    parcel2.writeNoException();
                    if (message2 != null) {
                        parcel2.writeInt(1);
                        message2.writeToParcel(parcel2, 1);
                    } else {
                        parcel2.writeInt(0);
                    }
                    return true;
                case 35:
                    parcel.enforceInterface(DESCRIPTOR);
                    int messageAsync = setMessageAsync(parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt() != 0 ? (Bundle) Bundle.CREATOR.createFromParcel(parcel) : null, IMessageCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    parcel2.writeInt(messageAsync);
                    return true;
                case 36:
                    parcel.enforceInterface(DESCRIPTOR);
                    int messageAsync2 = getMessageAsync(parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt() != 0 ? (Bundle) Bundle.CREATOR.createFromParcel(parcel) : null, IMessageCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    parcel2.writeInt(messageAsync2);
                    return true;
                case 37:
                    parcel.enforceInterface(DESCRIPTOR);
                    sendBuiltInMicKaraokeModeAction(parcel.readInt(), parcel.readInt(), parcel.readInt(), parcel.readInt() != 0 ? (Bundle) Bundle.CREATOR.createFromParcel(parcel) : null, IStartKaraokeModeCallback.Stub.asInterface(parcel.readStrongBinder()));
                    parcel2.writeNoException();
                    return true;
                case 38:
                    parcel.enforceInterface(DESCRIPTOR);
                    registerMessageCallback(parcel.readInt(), parcel.readString(), parcel.readInt() != 0 ? (Bundle) Bundle.CREATOR.createFromParcel(parcel) : null, IMessageCallback2.Stub.asInterface(parcel.readStrongBinder()), parcel.readStrongBinder());
                    return true;
                case 39:
                    parcel.enforceInterface(DESCRIPTOR);
                    unregisterMessageCallback(parcel.readInt(), parcel.readString(), parcel.readInt() != 0 ? (Bundle) Bundle.CREATOR.createFromParcel(parcel) : null, IMessageCallback2.Stub.asInterface(parcel.readStrongBinder()), parcel.readStrongBinder());
                    return true;
                default:
                    return super.onTransact(i, parcel, parcel2, i2);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public static class Proxy implements IMicrophoneService {
            public static IMicrophoneService sDefaultImpl;
            private IBinder mRemote;

            public String getInterfaceDescriptor() {
                return Stub.DESCRIPTOR;
            }

            Proxy(IBinder iBinder) {
                this.mRemote = iBinder;
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.mRemote;
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void registerMicrophoneConnectionStateListener(IConnectionStateListener iConnectionStateListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iConnectionStateListener != null ? iConnectionStateListener.asBinder() : null);
                    if (!this.mRemote.transact(1, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().registerMicrophoneConnectionStateListener(iConnectionStateListener);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void unregisterMicrophoneConnectionStateListener(IConnectionStateListener iConnectionStateListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iConnectionStateListener != null ? iConnectionStateListener.asBinder() : null);
                    if (!this.mRemote.transact(2, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().unregisterMicrophoneConnectionStateListener(iConnectionStateListener);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void setBufferCallback(IBufferCallback iBufferCallback) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iBufferCallback != null ? iBufferCallback.asBinder() : null);
                    if (!this.mRemote.transact(3, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().setBufferCallback(iBufferCallback);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public int sendBuffer(byte[] bArr) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeByteArray(bArr);
                    if (!this.mRemote.transact(4, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().sendBuffer(bArr);
                    }
                    obtain2.readException();
                    return obtain2.readInt();
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void registerErrorListener(IErrorListener iErrorListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iErrorListener != null ? iErrorListener.asBinder() : null);
                    if (!this.mRemote.transact(5, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().registerErrorListener(iErrorListener);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void unregisterErrorListener(IErrorListener iErrorListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iErrorListener != null ? iErrorListener.asBinder() : null);
                    if (!this.mRemote.transact(6, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().unregisterErrorListener(iErrorListener);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public boolean increaseMicVolume() throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(7, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().increaseMicVolume();
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public boolean decreaseMicVolume() throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(8, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().decreaseMicVolume();
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public boolean setMicVolume(int i) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    if (!this.mRemote.transact(9, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().setMicVolume(i);
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public int getMicVolume() throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(10, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getMicVolume();
                    }
                    obtain2.readException();
                    return obtain2.readInt();
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public boolean getMicConnectionState() throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(11, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getMicConnectionState();
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public boolean setMicVolumeWithShow(int i, boolean z) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    obtain.writeInt(z ? 1 : 0);
                    if (!this.mRemote.transact(12, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().setMicVolumeWithShow(i, z);
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void startBuiltInMicKaraokeMode(int i) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    if (!this.mRemote.transact(13, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().startBuiltInMicKaraokeMode(i);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void stopBuiltInMicKaraokeMode(int i) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    if (!this.mRemote.transact(14, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().stopBuiltInMicKaraokeMode(i);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void registerKaraokeModeListener(IKaraokeModeListener iKaraokeModeListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iKaraokeModeListener != null ? iKaraokeModeListener.asBinder() : null);
                    if (!this.mRemote.transact(15, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().registerKaraokeModeListener(iKaraokeModeListener);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void unregisterKaraokeModeListener(IKaraokeModeListener iKaraokeModeListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iKaraokeModeListener != null ? iKaraokeModeListener.asBinder() : null);
                    if (!this.mRemote.transact(16, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().unregisterKaraokeModeListener(iKaraokeModeListener);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public int getKaraokeMode() throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(17, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getKaraokeMode();
                    }
                    obtain2.readException();
                    return obtain2.readInt();
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public boolean isBuiltInMicKaraokeModeSupport() throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(18, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().isBuiltInMicKaraokeModeSupport();
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public List<MicrophoneDevice> getDeviceList() throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(19, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getDeviceList();
                    }
                    obtain2.readException();
                    return obtain2.createTypedArrayList(MicrophoneDevice.CREATOR);
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public int getReverberation() throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(20, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getReverberation();
                    }
                    obtain2.readException();
                    return obtain2.readInt();
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public boolean setReverberation(int i) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    if (!this.mRemote.transact(21, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().setReverberation(i);
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public int getEffect() throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (!this.mRemote.transact(22, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getEffect();
                    }
                    obtain2.readException();
                    return obtain2.readInt();
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public boolean setEffect(int i) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    if (!this.mRemote.transact(23, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().setEffect(i);
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void registerSettingListener(ISettingListener iSettingListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iSettingListener != null ? iSettingListener.asBinder() : null);
                    if (!this.mRemote.transact(24, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().registerSettingListener(iSettingListener);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void unregisterSettingListener(ISettingListener iSettingListener) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iSettingListener != null ? iSettingListener.asBinder() : null);
                    if (!this.mRemote.transact(25, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().unregisterSettingListener(iSettingListener);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void registerBuiltinMicKaraokeClient(String str, int i, IBinder iBinder) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    obtain.writeInt(i);
                    obtain.writeStrongBinder(iBinder);
                    if (!this.mRemote.transact(26, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().registerBuiltinMicKaraokeClient(str, i, iBinder);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void unregisterBuiltinMicKaraokeClient(IBinder iBinder) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iBinder);
                    if (!this.mRemote.transact(27, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().unregisterBuiltinMicKaraokeClient(iBinder);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void startBuiltInMicKaraokeModeWithCallback(int i, IStartKaraokeModeCallback iStartKaraokeModeCallback) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    obtain.writeStrongBinder(iStartKaraokeModeCallback != null ? iStartKaraokeModeCallback.asBinder() : null);
                    if (!this.mRemote.transact(28, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().startBuiltInMicKaraokeModeWithCallback(i, iStartKaraokeModeCallback);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public boolean setAudioManagerParameters(String str) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    if (!this.mRemote.transact(29, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().setAudioManagerParameters(str);
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public String getAudioManagerParameters(String str) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeString(str);
                    if (!this.mRemote.transact(30, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getAudioManagerParameters(str);
                    }
                    obtain2.readException();
                    return obtain2.readString();
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void registerBufferCallback(IBufferCallback iBufferCallback) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iBufferCallback != null ? iBufferCallback.asBinder() : null);
                    if (!this.mRemote.transact(31, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().registerBufferCallback(iBufferCallback);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void unregisterBufferCallback(IBufferCallback iBufferCallback) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeStrongBinder(iBufferCallback != null ? iBufferCallback.asBinder() : null);
                    if (!this.mRemote.transact(32, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().unregisterBufferCallback(iBufferCallback);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public int setMessage(int i, int i2, int i3, Bundle bundle) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    obtain.writeInt(i2);
                    obtain.writeInt(i3);
                    if (bundle != null) {
                        obtain.writeInt(1);
                        bundle.writeToParcel(obtain, 0);
                    } else {
                        obtain.writeInt(0);
                    }
                    if (!this.mRemote.transact(33, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().setMessage(i, i2, i3, bundle);
                    }
                    obtain2.readException();
                    return obtain2.readInt();
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public Bundle getMessage(int i, int i2, int i3, Bundle bundle) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    obtain.writeInt(i2);
                    obtain.writeInt(i3);
                    if (bundle != null) {
                        obtain.writeInt(1);
                        bundle.writeToParcel(obtain, 0);
                    } else {
                        obtain.writeInt(0);
                    }
                    if (!this.mRemote.transact(34, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getMessage(i, i2, i3, bundle);
                    }
                    obtain2.readException();
                    return obtain2.readInt() != 0 ? (Bundle) Bundle.CREATOR.createFromParcel(obtain2) : null;
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public int setMessageAsync(int i, int i2, int i3, Bundle bundle, IMessageCallback iMessageCallback) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    obtain.writeInt(i2);
                    obtain.writeInt(i3);
                    if (bundle != null) {
                        obtain.writeInt(1);
                        bundle.writeToParcel(obtain, 0);
                    } else {
                        obtain.writeInt(0);
                    }
                    obtain.writeStrongBinder(iMessageCallback != null ? iMessageCallback.asBinder() : null);
                    if (!this.mRemote.transact(35, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().setMessageAsync(i, i2, i3, bundle, iMessageCallback);
                    }
                    obtain2.readException();
                    return obtain2.readInt();
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public int getMessageAsync(int i, int i2, int i3, Bundle bundle, IMessageCallback iMessageCallback) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    obtain.writeInt(i2);
                    obtain.writeInt(i3);
                    if (bundle != null) {
                        obtain.writeInt(1);
                        bundle.writeToParcel(obtain, 0);
                    } else {
                        obtain.writeInt(0);
                    }
                    obtain.writeStrongBinder(iMessageCallback != null ? iMessageCallback.asBinder() : null);
                    if (!this.mRemote.transact(36, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getMessageAsync(i, i2, i3, bundle, iMessageCallback);
                    }
                    obtain2.readException();
                    return obtain2.readInt();
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void sendBuiltInMicKaraokeModeAction(int i, int i2, int i3, Bundle bundle, IStartKaraokeModeCallback iStartKaraokeModeCallback) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                Parcel obtain2 = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    obtain.writeInt(i2);
                    obtain.writeInt(i3);
                    if (bundle != null) {
                        obtain.writeInt(1);
                        bundle.writeToParcel(obtain, 0);
                    } else {
                        obtain.writeInt(0);
                    }
                    obtain.writeStrongBinder(iStartKaraokeModeCallback != null ? iStartKaraokeModeCallback.asBinder() : null);
                    if (!this.mRemote.transact(37, obtain, obtain2, 0) && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().sendBuiltInMicKaraokeModeAction(i, i2, i3, bundle, iStartKaraokeModeCallback);
                    } else {
                        obtain2.readException();
                    }
                } finally {
                    obtain2.recycle();
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void registerMessageCallback(int i, String str, Bundle bundle, IMessageCallback2 iMessageCallback2, IBinder iBinder) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    obtain.writeString(str);
                    if (bundle != null) {
                        obtain.writeInt(1);
                        bundle.writeToParcel(obtain, 0);
                    } else {
                        obtain.writeInt(0);
                    }
                    obtain.writeStrongBinder(iMessageCallback2 != null ? iMessageCallback2.asBinder() : null);
                    obtain.writeStrongBinder(iBinder);
                    if (this.mRemote.transact(38, obtain, null, 1) || Stub.getDefaultImpl() == null) {
                        return;
                    }
                    Stub.getDefaultImpl().registerMessageCallback(i, str, bundle, iMessageCallback2, iBinder);
                } finally {
                    obtain.recycle();
                }
            }

            @Override // com.byd.minikaraoke.IMicrophoneService
            public void unregisterMessageCallback(int i, String str, Bundle bundle, IMessageCallback2 iMessageCallback2, IBinder iBinder) throws RemoteException {
                Parcel obtain = Parcel.obtain();
                try {
                    obtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    obtain.writeInt(i);
                    obtain.writeString(str);
                    if (bundle != null) {
                        obtain.writeInt(1);
                        bundle.writeToParcel(obtain, 0);
                    } else {
                        obtain.writeInt(0);
                    }
                    obtain.writeStrongBinder(iMessageCallback2 != null ? iMessageCallback2.asBinder() : null);
                    obtain.writeStrongBinder(iBinder);
                    if (this.mRemote.transact(39, obtain, null, 1) || Stub.getDefaultImpl() == null) {
                        return;
                    }
                    Stub.getDefaultImpl().unregisterMessageCallback(i, str, bundle, iMessageCallback2, iBinder);
                } finally {
                    obtain.recycle();
                }
            }
        }

        public static boolean setDefaultImpl(IMicrophoneService iMicrophoneService) {
            if (Proxy.sDefaultImpl != null) {
                throw new IllegalStateException("setDefaultImpl() called twice");
            }
            if (iMicrophoneService == null) {
                return false;
            }
            Proxy.sDefaultImpl = iMicrophoneService;
            return true;
        }

        public static IMicrophoneService getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }
    }
}
