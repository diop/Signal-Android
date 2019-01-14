package org.thoughtcrime.securesms.mesh.managers;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.support.v4.content.ContextCompat;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;

import com.gotenna.sdk.GoTenna;
import com.gotenna.sdk.bluetooth.GTConnectionManager;
import com.gotenna.sdk.commands.GTCommand;
import com.gotenna.sdk.commands.GTCommandCenter;
import com.gotenna.sdk.commands.GTError;
import com.gotenna.sdk.commands.Place;
import com.gotenna.sdk.exceptions.GTInvalidAppTokenException;
import com.gotenna.sdk.interfaces.GTErrorListener;
import com.gotenna.sdk.messages.GTBaseMessageData;
import com.gotenna.sdk.messages.GTGroupCreationMessageData;
import com.gotenna.sdk.messages.GTMessageData;
import com.gotenna.sdk.messages.GTTextOnlyMessageData;
import com.gotenna.sdk.responses.GTResponse;
import com.gotenna.sdk.types.GTDataTypes;
import com.gotenna.sdk.utils.Utils;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.mesh.models.Message;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.threeten.bp.Instant;
import org.threeten.bp.LocalDateTime;
import org.threeten.bp.format.DateTimeFormatter;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;

import java.util.ArrayList;
import java.util.Date;

import android.telephony.SmsMessage;

import static android.telephony.SmsMessage.getSubmitPdu;

/**
 * A singleton that manages listening for incoming messages from the SDK and parses them into
 * usable data classes.
 * <p>
 * Created on 2/10/16
 *
 * @author ThomasColligan
 */
public class GTMeshManager implements GTCommandCenter.GTMessageListener, GTConnectionManager.GTConnectionListener, GTCommand.GTCommandResponseListener, GTErrorListener
{
    //==============================================================================================
    // Class Properties
    //==============================================================================================

    private static final String GOTENNA_APP_TOKEN = "your token goes here";// TODO: Insert your token
    private static final int SCAN_TIMEOUT = 25000; // 25 seconds
    private static Context applicationContext;

    private GTConnectionManager gtConnectionManager = null;
    private Handler handler;
    // private NetworkingActivity callbackActivity;

    // set in ConversationActivity
    private boolean isSecureText          = false;
    private boolean isDefaultSms          = false;
    private boolean isSecurityInitialized = true;
    private Recipient recipient;
    private String connectedAddress;

    private final ArrayList<IncomingMessageListener> incomingMessageListeners;

    // set in
    private PendingIntent gtSentIntent;
    private PendingIntent gtDeliveryIntent;

    //==============================================================================================
    // Singleton Methods
    //==============================================================================================

    private GTMeshManager()
    {
        incomingMessageListeners = new ArrayList<>();
    }

    private static class SingletonHelper
    {
        private static final GTMeshManager INSTANCE = new GTMeshManager();
    }

    public static GTMeshManager getInstance()
    {
        return SingletonHelper.INSTANCE;
    }

    public static long getGidFromPhoneNumber(final String phoneNumber) { return Long.parseLong(phoneNumber.replaceAll("[^0-9]", "")); }

    //==============================================================================================
    // Class Instance Methods
    //==============================================================================================

    public void initToken(Context context) {

        applicationContext = context;
        try {
            GoTenna.setApplicationToken(context.getApplicationContext(), GOTENNA_APP_TOKEN);
            if(GoTenna.tokenIsVerified())    {
                Log.d("GTMeshManager", "goTenna token is verified:" + GoTenna.tokenIsVerified());
            }
            gtConnectionManager = GTConnectionManager.getInstance();
            startListening();

            handler = new Handler(Looper.getMainLooper()) {
                @Override
                public void handleMessage(android.os.Message msg){
                    if(msg.what == 0) {
                        // no connection, scanning timed out
                        connectedAddress = "";
                    }
                }
            };
        }
        catch(GTInvalidAppTokenException e) {
            e.printStackTrace();
        }
    }

    public void startListening()
    {
        GTCommandCenter.getInstance().setMessageListener(this);
    }

    public void addIncomingMessageListener(IncomingMessageListener incomingMessageListener)
    {
        synchronized (incomingMessageListeners)
        {
            if (incomingMessageListener != null)
            {
                incomingMessageListeners.remove(incomingMessageListener);
                incomingMessageListeners.add(incomingMessageListener);
            }
        }
    }

    public void removeIncomingMessageListener(IncomingMessageListener incomingMessageListener)
    {
        synchronized (incomingMessageListeners)
        {
            if (incomingMessageListener != null)
            {
                incomingMessageListeners.remove(incomingMessageListener);
            }
        }
    }

    private void notifyIncomingMessage(final Message incomingMessage)
    {
        synchronized (incomingMessageListeners)
        {
            for (IncomingMessageListener incomingMessageListener : incomingMessageListeners)
            {
                incomingMessageListener.onIncomingMessage(incomingMessage);
            }
        }
    }

    // private void showGroupInvitationToast(long groupGID)
    // {
    //     Context context = MyApplication.getAppContext();
    //     String message = context.getString(R.string.invited_to_group_toast_text, groupGID);
    //
    //     Toast toast = Toast.makeText(context, message, Toast.LENGTH_LONG);
    //     toast.setGravity(Gravity.CENTER, 0, 0);
    //    toast.show();
    // }

    //==============================================================================================
    // GTMessageListener Implementation
    //==============================================================================================

    @Override
    public void onIncomingMessage(GTMessageData messageData)
    {
        // We do not send any custom formatted messages in this app,
        // but if you wanted to send out messages with your own format, this is where
        // you would receive those messages.
        // TODO: parse custom messages?
    }

    @Override
    public void onIncomingMessage(GTBaseMessageData gtBaseMessageData)
    {
        if (gtBaseMessageData instanceof GTTextOnlyMessageData)
        {
            // Somebody sent us a message, try to parse it
            GTTextOnlyMessageData gtTextOnlyMessageData = (GTTextOnlyMessageData) gtBaseMessageData;
            Message incomingMessage = Message.createMessageFromData(gtTextOnlyMessageData);
            notifyIncomingMessage(incomingMessage);
        }
        else if (gtBaseMessageData instanceof GTGroupCreationMessageData)
        {
            // Somebody invited us to a group!
            GTGroupCreationMessageData gtGroupCreationMessageData = (GTGroupCreationMessageData) gtBaseMessageData;
            // showGroupInvitationToast(gtGroupCreationMessageData.getGroupGID());
        }
    }

    //==============================================================================================
    // IncomingMessageListener Interface
    //==============================================================================================

    public interface IncomingMessageListener
    {
        void onIncomingMessage(Message incomingMessage);
    }

    // derived from SmsReceiveJob.storeMessage
    public Optional<MessagingDatabase.InsertResult> storeMessage(final Message gtMessage)
    {
        SmsDatabase database = DatabaseFactory.getSmsDatabase(applicationContext);
        database.ensureMigration();

        if (TextSecurePreferences.getNeedsSqlCipherMigration(applicationContext)) {
            // TODO: throw new SmsReceiveJob.MigrationPendingException();
        }

        final String senderGID = Long.toString(gtMessage.getSenderGID());
        Log.d("GTMeshManager", "storeMessage from sender GID:" + senderGID);

        Address sender = Address.fromExternal(applicationContext, senderGID);
        Optional<SignalServiceGroup> group = Optional.absent();
        boolean unidentified = false;
        IncomingTextMessage message = new IncomingTextMessage(sender, 0, System.currentTimeMillis(), gtMessage.getText(), group,0,unidentified);

        if (message.isSecureMessage()) {
            IncomingTextMessage placeholder  = new IncomingTextMessage(message, "");
            Optional<MessagingDatabase.InsertResult> insertResult = database.insertMessageInbox(placeholder);
            database.markAsLegacyVersion(insertResult.get().getMessageId());

            return insertResult;
        } else {
            return database.insertMessageInbox(message);
        }
    }

    public boolean isPaired()  {

        if(gtConnectionManager.getGtConnectionState() == GTConnectionManager.GTConnectionState.CONNECTED) {
            return (gtConnectionManager.getConnectedGotennaAddress() != null);
        }
        return false;
    }

    public void setGeoloc(int region){
        Place place = null;
        switch(region)    {
            case 1:
                place = Place.EUROPE;
                break;
            case 2:
                place = Place.AUSTRALIA;
                break;
            case 3:
                place = Place.NEW_ZEALAND;
                break;
            case 4:
                place = Place.SINGAPORE;
                break;
            default:
                place = Place.NORTH_AMERICA;
                break;
        }

        if (isPaired()) {
            GTCommandCenter.getInstance().sendSetGeoRegion(place, new GTCommand.GTCommandResponseListener() {
                @Override
                public void onResponse(GTResponse response) {
                    if (response.getResponseCode() == GTDataTypes.GTCommandResponseCode.POSITIVE) {
                        Log.d("GTMeshManager", "Region set OK");
                    } else {
                        Log.d("GTMeshManager", "Region set:" + response.toString());
                    }
                }
            }, new GTErrorListener() {
                @Override
                public void onError(GTError error) {
                    Log.d("GTMeshManager", error.toString() + "," + error.getCode());
                }
            });
        }
    }

    private boolean hasBluetoothPermisson() {
        return ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationpermission() {
        return ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public void disconnect() {
        gtConnectionManager.addGtConnectionListener(this);
        gtConnectionManager.disconnect();
    }

    public void connect() {

        if(hasBluetoothPermisson() && hasLocationpermission()) {
            gtConnectionManager.addGtConnectionListener(this);
            gtConnectionManager.clearConnectedGotennaAddress();
            gtConnectionManager.scanAndConnect(GTConnectionManager.GTDeviceType.MESH);
            handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT);
        }
    }

    @Override
    public void onConnectionStateUpdated(GTConnectionManager.GTConnectionState gtConnectionState) {
        switch (gtConnectionState) {
            case CONNECTED: {
                connectedAddress = gtConnectionManager.getConnectedGotennaAddress();
                Log.d("GTMeshManager", "existing connected address:" + connectedAddress);
            }
            break;
            case DISCONNECTED: {
                Log.d("GTMeshManager", "no connection");
                connectedAddress = "";
            }
            break;
            case SCANNING: {
                Log.d("GTMeshManager", "scanning for connection");
            }
            break;
        }
        if (gtConnectionState != GTConnectionManager.GTConnectionState.SCANNING) {
            gtConnectionManager.removeGtConnectionListener(this);
            handler.removeCallbacks(scanTimeoutRunnable);
            connectedAddress = "";
        }
    }

    private final Runnable scanTimeoutRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            handler.removeCallbacks(scanTimeoutRunnable);
            handler.sendEmptyMessage(0);
        }
    };

    @Override
    public void onResponse(final GTResponse gtResponse) {

        Log.d("GTMeshManager", "onResponse: " + gtResponse.toString());
        try {
            if (gtResponse.getResponseCode() == GTDataTypes.GTCommandResponseCode.POSITIVE) {
                gtSentIntent.send(Activity.RESULT_OK); // Sent!
                gtDeliveryIntent.send();  // Delivered!
            }
            else if (gtResponse.getResponseCode() == GTDataTypes.GTCommandResponseCode.NEGATIVE) {
                // TODO: map responses properly to intent parsing
                gtSentIntent.send(SmsManager.RESULT_ERROR_NO_SERVICE); // FAILED_TYPE
            }
            else {
                // TODO: map responses properly to intent parsing
                gtSentIntent.send(SmsManager.RESULT_ERROR_GENERIC_FAILURE); // FAILED_TYPE
            }
        }
        catch(PendingIntent.CanceledException e) {
            // ignore
        }
    }

    @Override
    public void onError(GTError gtError){
        Log.d("GTMeshManager", "onError: " + gtError.toString());
        if (gtDeliveryIntent != null) {
            try {
                // TODO: map responses properly to intent parsing
                gtSentIntent.send(SmsManager.RESULT_ERROR_GENERIC_FAILURE); // FAILED_TYPE
            } catch (PendingIntent.CanceledException e) {
                // ignore
            }
        }
    }

    public void sendTextMessageInternal(String destinationAddress, String scAddress,
                                         String text, PendingIntent sentIntent, PendingIntent deliveryIntent,
                                         boolean persistMessage) {

        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }

        if (TextUtils.isEmpty(text)) {
            throw new IllegalArgumentException("Invalid message body");
        }

        // TODO: set willEncrypt to true
        final Address localAddress = Address.fromSerialized(TextSecurePreferences.getLocalNumber(applicationContext));
        long senderGID = getGidFromPhoneNumber(localAddress.toPhoneString());
        long receiverGID = getGidFromPhoneNumber(destinationAddress);

        Date localDateTime = new Date(Instant.now().toEpochMilli());
        Message gtMessage = new Message(senderGID, receiverGID, localDateTime, text, Message.MessageStatus.SENDING, "");

        GTCommandCenter.getInstance().sendMessage(gtMessage.toBytes(), receiverGID,
                this, this,
                false);

        gtSentIntent = sentIntent;
        gtDeliveryIntent = deliveryIntent;

        //iccISms.sendTextForSubscriber(getSubscriptionId(), ActivityThread.currentPackageName(),
        //        destinationAddress,
        //        scAddress, text, sentIntent, deliveryIntent,
        //        persistMessage);
    }
}