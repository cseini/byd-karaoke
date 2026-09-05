package com.byd.minikaraoke.api;

import android.os.Parcel;
import android.os.Parcelable;

/* loaded from: classes.dex */
public class MicrophoneDevice implements Parcelable {
    public static final Parcelable.Creator<MicrophoneDevice> CREATOR = new Parcelable.Creator<MicrophoneDevice>() { // from class: com.byd.minikaraoke.api.MicrophoneDevice.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public MicrophoneDevice createFromParcel(Parcel parcel) {
            return new MicrophoneDevice(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public MicrophoneDevice[] newArray(int i) {
            return new MicrophoneDevice[i];
        }
    };
    private String deviceModel;
    private String deviceName;
    private int firmwareSubVersion;
    private int firmwareVersion;
    private String firmwareVersionName;
    private String mac;
    private int micNum;
    private String serialNumber;
    private String vendorId;
    private String vendorName;

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public String getDeviceName() {
        return this.deviceName;
    }

    public void setDeviceName(String str) {
        this.deviceName = str;
    }

    public String getVendorName() {
        return this.vendorName;
    }

    public void setVendorName(String str) {
        this.vendorName = str;
    }

    public String getVendorId() {
        return this.vendorId;
    }

    public void setVendorId(String str) {
        this.vendorId = str;
    }

    public String getDeviceModel() {
        return this.deviceModel;
    }

    public void setDeviceModel(String str) {
        this.deviceModel = str;
    }

    public String getFirmwareVersionName() {
        return this.firmwareVersionName;
    }

    public void setFirmwareVersionName(String str) {
        this.firmwareVersionName = str;
    }

    public int getFirmwareVersion() {
        return this.firmwareVersion;
    }

    public void setFirmwareVersion(int i) {
        this.firmwareVersion = i;
    }

    public int getFirmwareSubVersion() {
        return this.firmwareSubVersion;
    }

    public void setFirmwareSubVersion(int i) {
        this.firmwareSubVersion = i;
    }

    public String getSerialNumber() {
        return this.serialNumber;
    }

    public void setSerialNumber(String str) {
        this.serialNumber = str;
    }

    public String getMac() {
        return this.mac;
    }

    public void setMac(String str) {
        this.mac = str;
    }

    public int getMicNum() {
        return this.micNum;
    }

    public void setMicNum(int i) {
        this.micNum = i;
    }

    public String toString() {
        return "MicDevice{deviceName='" + this.deviceName + "', deviceModel='" + this.deviceModel + "', vendorName='" + this.vendorName + "', vendorId='" + this.vendorId + "', firmwareVersionName='" + this.firmwareVersionName + "', firmwareVersion=" + this.firmwareVersion + ", firmwareSubVersion=" + this.firmwareSubVersion + ", serialNumber='" + this.serialNumber + "', mac='" + this.mac + "', micNum=" + this.micNum + '}';
    }

    public MicrophoneDevice() {
        this.firmwareSubVersion = 0;
    }

    protected MicrophoneDevice(Parcel parcel) {
        this.firmwareSubVersion = 0;
        this.deviceName = parcel.readString();
        this.deviceModel = parcel.readString();
        this.vendorName = parcel.readString();
        this.vendorId = parcel.readString();
        this.firmwareVersionName = parcel.readString();
        this.firmwareVersion = parcel.readInt();
        this.firmwareSubVersion = parcel.readInt();
        this.serialNumber = parcel.readString();
        this.mac = parcel.readString();
        this.micNum = parcel.readInt();
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(this.deviceName);
        parcel.writeString(this.deviceModel);
        parcel.writeString(this.vendorName);
        parcel.writeString(this.vendorId);
        parcel.writeString(this.firmwareVersionName);
        parcel.writeInt(this.firmwareVersion);
        parcel.writeInt(this.firmwareSubVersion);
        parcel.writeString(this.serialNumber);
        parcel.writeString(this.mac);
        parcel.writeInt(this.micNum);
    }
}
