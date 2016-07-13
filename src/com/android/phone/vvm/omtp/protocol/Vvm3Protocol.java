/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.phone.vvm.omtp.protocol;

import android.annotation.Nullable;
import android.content.Context;
import android.net.Network;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.telephony.SmsManager;

import com.android.phone.common.mail.MessagingException;
import com.android.phone.settings.VisualVoicemailSettingsUtil;
import com.android.phone.settings.VoicemailChangePinActivity;
import com.android.phone.vvm.omtp.ActivationTask;
import com.android.phone.vvm.omtp.OmtpConstants;
import com.android.phone.vvm.omtp.OmtpEvents;
import com.android.phone.vvm.omtp.OmtpVvmCarrierConfigHelper;
import com.android.phone.vvm.omtp.VisualVoicemailPreferences;
import com.android.phone.vvm.omtp.VvmLog;
import com.android.phone.vvm.omtp.imap.ImapHelper;
import com.android.phone.vvm.omtp.sms.OmtpMessageSender;
import com.android.phone.vvm.omtp.sms.StatusMessage;
import com.android.phone.vvm.omtp.sms.Vvm3MessageSender;
import com.android.phone.vvm.omtp.sync.VvmNetworkRequest;
import com.android.phone.vvm.omtp.sync.VvmNetworkRequest.NetworkWrapper;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * A flavor of OMTP protocol with a different provisioning process
 *
 * Used by carriers such as Verizon Wireless
 */
public class Vvm3Protocol extends VisualVoicemailProtocol {

    private static String TAG = "Vvm3Protocol";

    private static String IMAP_CHANGE_TUI_PWD_FORMAT = "CHANGE_TUI_PWD PWD=%1$s OLD_PWD=%2$s";
    private static String IMAP_CHANGE_VM_LANG_FORMAT = "CHANGE_VM_LANG Lang=%1$s";
    private static String IMAP_CLOSE_NUT = "CLOSE_NUT";

    private static String ISO639_Spanish = "es";

    /**
     * For VVM3, if the STATUS SMS returns {@link StatusMessage#getProvisioningStatus()} of {@link
     * OmtpConstants#SUBSCRIBER_UNKNOWN} and {@link StatusMessage#getReturnCode()} of this value,
     * the user can self-provision visual voicemail service. For other response codes, the user must
     * contact customer support to resolve the issue.
     */
    private static final String VVM3_UNKNOWN_SUBSCRIBER_CAN_SUBSCRIBE_RESPONSE_CODE = "2";

    // Default prompt level when using the telephone user interface.
    // Standard prompt when the user call into the voicemail, and no prompts when someone else is
    // leaving a voicemail.
    private static final String VVM3_VM_LANGUAGE_ENGLISH_STANDARD_NO_GUEST_PROMPTS = "5";
    private static final String VVM3_VM_LANGUAGE_SPANISH_STANDARD_NO_GUEST_PROMPTS = "6";

    private static final int DEFAULT_PIN_LENGTH = 6;

    @Override
    public void startActivation(OmtpVvmCarrierConfigHelper config) {
        // VVM3 does not support activation SMS.
        // Send a status request which will start the provisioning process if the user is not
        // provisioned.
        VvmLog.i(TAG, "Activating");
        config.requestStatus();
    }

    @Override
    public void startDeactivation(OmtpVvmCarrierConfigHelper config) {
        // VVM3 does not support deactivation.
        // do nothing.
    }

    @Override
    public boolean supportsProvisioning() {
        return true;
    }

    @Override
    public void startProvisioning(ActivationTask task, PhoneAccountHandle phoneAccountHandle,
            OmtpVvmCarrierConfigHelper config, StatusMessage message, Bundle data) {
        VvmLog.i(TAG, "start vvm3 provisioning");
        if (OmtpConstants.SUBSCRIBER_UNKNOWN.equals(message.getProvisioningStatus())) {
            VvmLog.i(TAG, "Provisioning status: Unknown");
            if (VVM3_UNKNOWN_SUBSCRIBER_CAN_SUBSCRIBE_RESPONSE_CODE
                    .equals(message.getReturnCode())) {
                VvmLog.i(TAG, "Self provisioning available, subscribing");
                new Vvm3Subscriber(task, phoneAccountHandle, config, data).subscribe();
            } else {
                config.handleEvent(OmtpEvents.VVM3_SUBSCRIBER_UNKNOWN);
            }
        } else if (OmtpConstants.SUBSCRIBER_NEW.equals(message.getProvisioningStatus())) {
            VvmLog.i(TAG, "setting up new user");
            // Save the IMAP credentials in preferences so they are persistent and can be retrieved.
            VisualVoicemailPreferences prefs =
                    new VisualVoicemailPreferences(config.getContext(), phoneAccountHandle);
            message.putStatus(prefs.edit()).apply();

            startProvisionNewUser(phoneAccountHandle, config, message);
        } else if (OmtpConstants.SUBSCRIBER_PROVISIONED.equals(message.getProvisioningStatus())) {
            VvmLog.i(TAG, "User provisioned but not activated, disabling VVM");
            VisualVoicemailSettingsUtil
                    .setEnabled(config.getContext(), phoneAccountHandle, false);
        } else if (OmtpConstants.SUBSCRIBER_BLOCKED.equals(message.getProvisioningStatus())) {
            VvmLog.i(TAG, "User blocked");
            config.handleEvent(OmtpEvents.VVM3_SUBSCRIBER_BLOCKED);
        }
    }

    @Override
    public OmtpMessageSender createMessageSender(SmsManager smsManager, short applicationPort,
            String destinationNumber) {
        return new Vvm3MessageSender(smsManager, applicationPort, destinationNumber);
    }

    @Override
    public void handleEvent(Context context, OmtpVvmCarrierConfigHelper config, OmtpEvents event) {
        Vvm3EventHandler.handleEvent(context, config, event);
    }

    @Override
    public String getCommand(String command) {
        if (command == OmtpConstants.IMAP_CHANGE_TUI_PWD_FORMAT) {
            return IMAP_CHANGE_TUI_PWD_FORMAT;
        }
        if (command == OmtpConstants.IMAP_CLOSE_NUT) {
            return IMAP_CLOSE_NUT;
        }
        if (command == OmtpConstants.IMAP_CHANGE_VM_LANG_FORMAT) {
            return IMAP_CHANGE_VM_LANG_FORMAT;
        }
        return super.getCommand(command);
    }

    private void startProvisionNewUser(PhoneAccountHandle phoneAccountHandle,
            OmtpVvmCarrierConfigHelper config, StatusMessage message) {
        try (NetworkWrapper wrapper = VvmNetworkRequest.getNetwork(config, phoneAccountHandle)) {
            Network network = wrapper.get();

            VvmLog.i(TAG, "new user: network available");
            ImapHelper helper = new ImapHelper(config.getContext(), phoneAccountHandle, network);
            try {
                // VVM3 has inconsistent error language code to OMTP. Just issue a raw command
                // here.
                // TODO(b/29082671): use LocaleList
                if (Locale.getDefault().getLanguage()
                        .equals(new Locale(ISO639_Spanish).getLanguage())) {
                    // Spanish
                    helper.changeVoicemailTuiLanguage(
                            VVM3_VM_LANGUAGE_SPANISH_STANDARD_NO_GUEST_PROMPTS);
                } else {
                    // English
                    helper.changeVoicemailTuiLanguage(
                            VVM3_VM_LANGUAGE_ENGLISH_STANDARD_NO_GUEST_PROMPTS);
                }
                VvmLog.i(TAG, "new user: language set");

                if (setPin(config.getContext(), phoneAccountHandle, helper, message)) {
                    // Only close new user tutorial if the PIN has been changed.
                    helper.closeNewUserTutorial();
                    VvmLog.i(TAG, "new user: NUT closed");

                    config.requestStatus();
                }
            } catch (MessagingException | IOException e) {
                helper.handleEvent(OmtpEvents.VVM3_NEW_USER_SETUP_FAILED);
                VvmLog.e(TAG, e.toString());
            } finally {
                helper.close();
            }

        }

    }


    private static boolean setPin(Context context, PhoneAccountHandle phoneAccountHandle,
            ImapHelper helper, StatusMessage message)
            throws IOException, MessagingException {
        String defaultPin = getDefaultPin(message);
        if (defaultPin == null) {
            VvmLog.i(TAG, "cannot generate default PIN");
            return false;
        }

        if (VoicemailChangePinActivity.isDefaultOldPinSet(context, phoneAccountHandle)) {
            // The pin was already set
            VvmLog.i(TAG, "PIN already set");
            return true;
        }
        String newPin = generatePin(getMinimumPinLength(context, phoneAccountHandle));
        if (helper.changePin(defaultPin, newPin) == OmtpConstants.CHANGE_PIN_SUCCESS) {
            VoicemailChangePinActivity.setDefaultOldPIN(context, phoneAccountHandle, newPin);
            helper.handleEvent(OmtpEvents.CONFIG_DEFAULT_PIN_REPLACED);
        }
        VvmLog.i(TAG, "new user: PIN set");
        return true;
    }

    @Nullable
    private static String getDefaultPin(StatusMessage message) {
        // The IMAP username is [phone number]@example.com
        String username = message.getImapUserName();
        try {
            String number = username.substring(0, username.indexOf('@'));
            if (number.length() < 4) {
                VvmLog.e(TAG, "unable to extract number from IMAP username");
                return null;
            }
            return "1" + number.substring(number.length() - 4);
        } catch (StringIndexOutOfBoundsException e) {
            VvmLog.e(TAG, "unable to extract number from IMAP username");
            return null;
        }

    }

    private static int getMinimumPinLength(Context context, PhoneAccountHandle phoneAccountHandle) {
        VisualVoicemailPreferences preferences = new VisualVoicemailPreferences(context,
                phoneAccountHandle);
        // The OMTP pin length format is {min}-{max}
        String[] lengths = preferences.getString(OmtpConstants.TUI_PASSWORD_LENGTH, "").split("-");
        if (lengths.length == 2) {
            try {
                return Integer.parseInt(lengths[0]);
            } catch (NumberFormatException e) {
                return DEFAULT_PIN_LENGTH;
            }
        }
        return DEFAULT_PIN_LENGTH;
    }

    private static String generatePin(int length) {
        SecureRandom random = new SecureRandom();
        return String.format(Locale.US, "%010d", Math.abs(random.nextLong()))
                .substring(0, length);

    }
}
