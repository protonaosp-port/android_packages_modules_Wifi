/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.NetworkInfo.DetailedState.CONNECTED;
import static android.net.NetworkInfo.DetailedState.CONNECTING;
import static android.net.NetworkInfo.DetailedState.OBTAINING_IPADDR;
import static android.net.wifi.WifiConfiguration.METERED_OVERRIDE_METERED;
import static android.net.wifi.WifiConfiguration.METERED_OVERRIDE_NONE;
import static android.net.wifi.WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_NONE;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_NO_INTERNET_TEMPORARY;

import static com.android.server.wifi.ClientModeImpl.CMD_PRE_DHCP_ACTION;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyByte;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.app.ActivityManager;
import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.app.test.TestAlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.net.ConnectivityManager;
import android.net.DhcpResultsParcelable;
import android.net.InetAddresses;
import android.net.Layer2InformationParcelable;
import android.net.Layer2PacketParcelable;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkSpecifier;
import android.net.StaticIpConfiguration;
import android.net.ip.IIpClient;
import android.net.ip.IpClientCallbacks;
import android.net.wifi.IActionListener;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkAgentSpecifier;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.BatteryStatsManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;
import android.util.Pair;

import androidx.test.filters.SmallTest;

import com.android.connectivity.aidl.INetworkAgent;
import com.android.connectivity.aidl.INetworkAgentRegistry;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IState;
import com.android.internal.util.StateMachine;
import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.ClientMode.LinkProbeCallback;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointProvisioningTestUtil;
import com.android.server.wifi.hotspot2.WnmData;
import com.android.server.wifi.proto.nano.WifiMetricsProto;
import com.android.server.wifi.proto.nano.WifiMetricsProto.StaEvent;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiIsUnusableEvent;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiUsabilityStats;
import com.android.server.wifi.util.ActionListenerWrapper;
import com.android.server.wifi.util.RssiUtilTest;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.wifi.resources.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Unit tests for {@link com.android.server.wifi.ClientModeImpl}.
 */
@SmallTest
public class ClientModeImplTest extends WifiBaseTest {
    public static final String TAG = "ClientModeImplTest";

    private static final int MANAGED_PROFILE_UID = 1100000;
    private static final int OTHER_USER_UID = 1200000;
    private static final int LOG_REC_LIMIT_IN_VERBOSE_MODE =
            (ActivityManager.isLowRamDeviceStatic()
                    ? ClientModeImpl.NUM_LOG_RECS_VERBOSE_LOW_MEMORY
                    : ClientModeImpl.NUM_LOG_RECS_VERBOSE);
    private static final int FRAMEWORK_NETWORK_ID = 0;
    private static final int PASSPOINT_NETWORK_ID = 1;
    private static final int TEST_RSSI = -54;
    private static final int TEST_NETWORK_ID = 54;
    private static final int WPS_SUPPLICANT_NETWORK_ID = 5;
    private static final int WPS_FRAMEWORK_NETWORK_ID = 10;
    private static final String DEFAULT_TEST_SSID = "\"GoogleGuest\"";
    private static final String OP_PACKAGE_NAME = "com.xxx";
    private static final int TEST_UID = Process.SYSTEM_UID + 1000;
    private static final MacAddress TEST_GLOBAL_MAC_ADDRESS =
            MacAddress.fromString("10:22:34:56:78:92");
    private static final MacAddress TEST_LOCAL_MAC_ADDRESS =
            MacAddress.fromString("2a:53:43:c3:56:21");
    private static final MacAddress TEST_DEFAULT_MAC_ADDRESS =
            MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS);

    // NetworkAgent creates threshold ranges with Integers
    private static final int RSSI_THRESHOLD_MAX = -30;
    private static final int RSSI_THRESHOLD_MIN = -76;
    // Threshold breach callbacks are called with bytes
    private static final byte RSSI_THRESHOLD_BREACH_MIN = -80;
    private static final byte RSSI_THRESHOLD_BREACH_MAX = -20;

    private static final int DATA_SUBID = 1;
    private static final int CARRIER_ID_1 = 100;

    private static final long TEST_BSSID = 0x112233445566L;
    private static final int TEST_DELAY_IN_SECONDS = 300;

    private static final int DEFINED_ERROR_CODE = 32764;
    private static final String TEST_TERMS_AND_CONDITIONS_URL =
            "https://policies.google.com/terms?hl=en-US";

    private long mBinderToken;
    private MockitoSession mSession;
    private TestNetworkParams mTestNetworkParams = new TestNetworkParams();

    /**
     * Helper class for setting the default parameters of the WifiConfiguration that gets used
     * in connect().
     */
    class TestNetworkParams {
        public boolean hasEverConnected = false;
    }

    private static <T> T mockWithInterfaces(Class<T> class1, Class<?>... interfaces) {
        return mock(class1, withSettings().extraInterfaces(interfaces));
    }

    private void enableDebugLogs() {
        mCmi.enableVerboseLogging(true);
    }

    private FrameworkFacade getFrameworkFacade() throws Exception {
        FrameworkFacade facade = mock(FrameworkFacade.class);

        doAnswer(new AnswerWithArguments() {
            public void answer(
                    Context context, String ifname, IpClientCallbacks callback) {
                mIpClientCallback = callback;
                callback.onIpClientCreated(mIpClient);
            }
        }).when(facade).makeIpClient(any(), anyString(), any());

        return facade;
    }

    private Context getContext() throws Exception {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)).thenReturn(true);

        Context context = mock(Context.class);
        when(context.getPackageManager()).thenReturn(mPackageManager);

        MockContentResolver mockContentResolver = new MockContentResolver();
        mockContentResolver.addProvider(Settings.AUTHORITY,
                new MockContentProvider(context) {
                    @Override
                    public Bundle call(String method, String arg, Bundle extras) {
                        return new Bundle();
                    }
                });
        when(context.getContentResolver()).thenReturn(mockContentResolver);

        when(context.getSystemService(Context.POWER_SERVICE)).thenReturn(mPowerManager);
        when(context.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        when(mPowerManager.isInteractive()).thenReturn(true);
        when(mPowerManager.newWakeLock(anyInt(), anyString())).thenReturn(
                mock(PowerManager.WakeLock.class));

        mAlarmManager = new TestAlarmManager();
        when(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(
                mAlarmManager.getAlarmManager());

        when(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(
                mConnectivityManager);

        when(context.getOpPackageName()).thenReturn(OP_PACKAGE_NAME);

        when(context.getSystemService(ActivityManager.class)).thenReturn(
                mock(ActivityManager.class));

        WifiP2pManager p2pm = mock(WifiP2pManager.class);
        when(context.getSystemService(WifiP2pManager.class)).thenReturn(p2pm);
        final CountDownLatch untilDone = new CountDownLatch(1);
        mP2pThread = new HandlerThread("WifiP2pMockThread") {
            @Override
            protected void onLooperPrepared() {
                untilDone.countDown();
            }
        };
        mP2pThread.start();
        untilDone.await();
        Handler handler = new Handler(mP2pThread.getLooper());
        when(p2pm.getP2pStateMachineMessenger()).thenReturn(new Messenger(handler));

        return context;
    }

    private MockResources getMockResources() {
        MockResources resources = new MockResources();
        return resources;
    }

    private IState getCurrentState() throws
            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = StateMachine.class.getDeclaredMethod("getCurrentState");
        method.setAccessible(true);
        return (IState) method.invoke(mCmi);
    }

    private static HandlerThread getCmiHandlerThread(ClientModeImpl cmi) throws
            NoSuchFieldException, InvocationTargetException, IllegalAccessException {
        Field field = StateMachine.class.getDeclaredField("mSmThread");
        field.setAccessible(true);
        return (HandlerThread) field.get(cmi);
    }

    private static void stopLooper(final Looper looper) throws Exception {
        new Handler(looper).post(new Runnable() {
            @Override
            public void run() {
                looper.quitSafely();
            }
        });
    }

    private void dumpState() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mCmi.dump(null, writer, null);
        writer.flush();
        Log.d(TAG, "ClientModeImpl state -" + stream.toString());
    }

    private static ScanDetail getGoogleGuestScanDetail(int rssi, String bssid, int freq) {
        ScanResult.InformationElement[] ie = new ScanResult.InformationElement[1];
        ie[0] = ScanResults.generateSsidIe(sSSID);
        NetworkDetail nd = new NetworkDetail(sBSSID, ie, new ArrayList<String>(), sFreq);
        ScanDetail detail = new ScanDetail(nd, sWifiSsid, bssid, "", rssi, freq,
                Long.MAX_VALUE, /* needed so that scan results aren't rejected because
                                   there older than scan start */
                ie, new ArrayList<String>(), ScanResults.generateIERawDatafromScanResultIE(ie));

        return detail;
    }

    private ArrayList<ScanDetail> getMockScanResults() {
        ScanResults sr = ScanResults.create(0, 2412, 2437, 2462, 5180, 5220, 5745, 5825);
        ArrayList<ScanDetail> list = sr.getScanDetailArrayList();

        list.add(getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        return list;
    }

    private void injectDhcpSuccess(DhcpResultsParcelable dhcpResults) {
        mIpClientCallback.onNewDhcpResults(dhcpResults);
        mIpClientCallback.onProvisioningSuccess(new LinkProperties());
    }

    private void injectDhcpFailure() {
        mIpClientCallback.onNewDhcpResults((DhcpResultsParcelable) null);
        mIpClientCallback.onProvisioningFailure(new LinkProperties());
    }

    static final String   sSSID = "\"GoogleGuest\"";
    static final String   SSID_NO_QUOTE = sSSID.replace("\"", "");
    static final WifiSsid sWifiSsid = WifiSsid.createFromAsciiEncoded(SSID_NO_QUOTE);
    static final String   sBSSID = "01:02:03:04:05:06";
    static final String   sBSSID1 = "02:01:04:03:06:05";
    static final int      sFreq = 2437;
    static final int      sFreq1 = 5240;
    static final String   WIFI_IFACE_NAME = "mockWlan";
    static final String sFilsSsid = "FILS-AP";

    ClientModeImpl mCmi;
    HandlerThread mWifiCoreThread;
    HandlerThread mP2pThread;
    HandlerThread mSyncThread;
    INetworkAgent  mNetworkAgentBinder;
    TestAlarmManager mAlarmManager;
    MockWifiMonitor mWifiMonitor;
    TestLooper mLooper;
    Context mContext;
    MockResources mResources;
    FrameworkFacade mFrameworkFacade;
    IpClientCallbacks mIpClientCallback;
    OsuProvider mOsuProvider;
    WifiConfiguration mConnectedNetwork;
    WifiCarrierInfoManager mWifiCarrierInfoManager;
    ExtendedWifiInfo mWifiInfo;

    @Mock SupplicantStateTracker mSupplicantStateTracker;
    @Mock WifiMetrics mWifiMetrics;
    @Mock WifiCountryCode mCountryCode;
    @Mock WifiInjector mWifiInjector;
    @Mock WifiLastResortWatchdog mWifiLastResortWatchdog;
    @Mock WifiBlocklistMonitor mWifiBlocklistMonitor;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock WifiNative mWifiNative;
    @Mock WifiScoreCard mWifiScoreCard;
    @Mock WifiHealthMonitor mWifiHealthMonitor;
    @Mock WifiTrafficPoller mWifiTrafficPoller;
    @Mock WifiConnectivityManager mWifiConnectivityManager;
    @Mock WifiStateTracker mWifiStateTracker;
    @Mock PasspointManager mPasspointManager;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock IIpClient mIpClient;
    @Mock TelephonyManager mTelephonyManager;
    @Mock TelephonyManager mDataTelephonyManager;
    @Mock WrongPasswordNotifier mWrongPasswordNotifier;
    @Mock Clock mClock;
    @Mock ScanDetailCache mScanDetailCache;
    @Mock WifiDiagnostics mWifiDiagnostics;
    @Mock ConnectivityManager mConnectivityManager;
    @Mock IProvisioningCallback mProvisioningCallback;
    @Mock WakeupController mWakeupController;
    @Mock WifiDataStall mWifiDataStall;
    @Mock WifiNetworkFactory mWifiNetworkFactory;
    @Mock UntrustedWifiNetworkFactory mUntrustedWifiNetworkFactory;
    @Mock OemPaidWifiNetworkFactory mOemPaidWifiNetworkFactory;
    @Mock OemPrivateWifiNetworkFactory mOemPrivateWifiNetworkFactory;
    @Mock WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    @Mock LinkProbeManager mLinkProbeManager;
    @Mock PackageManager mPackageManager;
    @Mock WifiLockManager mWifiLockManager;
    @Mock AsyncChannel mNullAsyncChannel;
    @Mock INetworkAgentRegistry mNetworkAgentRegistry;
    @Mock BatteryStatsManager mBatteryStatsManager;
    @Mock MboOceController mMboOceController;
    @Mock SubscriptionManager mSubscriptionManager;
    @Mock ConnectionFailureNotifier mConnectionFailureNotifier;
    @Mock EapFailureNotifier mEapFailureNotifier;
    @Mock SimRequiredNotifier mSimRequiredNotifier;
    @Mock ThroughputPredictor mThroughputPredictor;
    @Mock ScanRequestProxy mScanRequestProxy;
    @Mock DeviceConfigFacade mDeviceConfigFacade;
    @Mock Network mNetwork;
    @Mock ConcreteClientModeManager mClientModeManager;
    @Mock WifiScoreReport mWifiScoreReport;
    @Mock PowerManager mPowerManager;
    @Mock WifiP2pConnection mWifiP2pConnection;
    @Mock WifiGlobals mWifiGlobals;
    @Mock LinkProbeCallback mLinkProbeCallback;
    @Mock ClientModeImplMonitor mCmiMonitor;

    final ArgumentCaptor<WifiConfigManager.OnNetworkUpdateListener> mConfigUpdateListenerCaptor =
            ArgumentCaptor.forClass(WifiConfigManager.OnNetworkUpdateListener.class);

    private void setUpWifiNative() throws Exception {
        when(mWifiNative.getStaFactoryMacAddress(WIFI_IFACE_NAME)).thenReturn(
                TEST_GLOBAL_MAC_ADDRESS);
        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_GLOBAL_MAC_ADDRESS.toString());
        WifiNative.ConnectionCapabilities cap = new WifiNative.ConnectionCapabilities();
        cap.wifiStandard = ScanResult.WIFI_STANDARD_11AC;
        when(mWifiNative.getConnectionCapabilities(WIFI_IFACE_NAME)).thenReturn(cap);
        when(mWifiNative.setStaMacAddress(eq(WIFI_IFACE_NAME), anyObject()))
                .then(new AnswerWithArguments() {
                    public boolean answer(String iface, MacAddress mac) {
                        when(mWifiNative.getMacAddress(iface)).thenReturn(mac.toString());
                        return true;
                    }
                });
        when(mWifiNative.connectToNetwork(any(), any())).thenReturn(true);
    }

    /** Reset verify() counters on WifiNative, and restore when() mocks on mWifiNative */
    private void resetWifiNative() throws Exception {
        reset(mWifiNative);
        setUpWifiNative();
    }

    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "Setting up ...");

        // Ensure looper exists
        mLooper = new TestLooper();

        MockitoAnnotations.initMocks(this);

        /** uncomment this to enable logs from ClientModeImpls */
        // enableDebugLogs();
        mWifiMonitor = spy(new MockWifiMonitor());
        when(mTelephonyManager.createForSubscriptionId(anyInt())).thenReturn(mDataTelephonyManager);
        when(mWifiNetworkFactory.getSpecificNetworkRequestUidAndPackageName(any()))
                .thenReturn(Pair.create(Process.INVALID_UID, ""));
        setUpWifiNative();
        doAnswer(new AnswerWithArguments() {
            public MacAddress answer(
                    WifiConfiguration config) {
                return config.getRandomizedMacAddress();
            }
        }).when(mWifiConfigManager).getRandomizedMacAndUpdateIfNeeded(any());

        mTestNetworkParams = new TestNetworkParams();
        when(mWifiNetworkFactory.hasConnectionRequests()).thenReturn(true);
        when(mUntrustedWifiNetworkFactory.hasConnectionRequests()).thenReturn(true);
        when(mOemPaidWifiNetworkFactory.hasConnectionRequests()).thenReturn(true);
        when(mOemPrivateWifiNetworkFactory.hasConnectionRequests()).thenReturn(true);

        mFrameworkFacade = getFrameworkFacade();
        mContext = getContext();
        mWifiInfo = new ExtendedWifiInfo(mWifiGlobals);

        when(mWifiGlobals.isConnectedMacRandomizationEnabled()).thenReturn(true);
        mResources = getMockResources();
        mResources.setIntArray(R.array.config_wifiRssiLevelThresholds,
                RssiUtilTest.RSSI_THRESHOLDS);
        when(mContext.getResources()).thenReturn(mResources);

        when(mWifiGlobals.getPollRssiIntervalMillis()).thenReturn(3000);
        when(mWifiGlobals.getIpReachabilityDisconnectEnabled()).thenReturn(true);

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_FREQUENCY_BAND,
                WifiManager.WIFI_FREQUENCY_BAND_AUTO)).thenReturn(
                WifiManager.WIFI_FREQUENCY_BAND_AUTO);
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        doAnswer(inv -> {
            mIpClientCallback.onQuit();
            return null;
        }).when(mIpClient).shutdown();
        when(mConnectivityManager.registerNetworkAgent(any(), any(), any(), any(), anyInt(), any(),
                anyInt())).thenReturn(mNetwork);
        List<SubscriptionInfo> subList = Arrays.asList(mock(SubscriptionInfo.class));
        when(mSubscriptionManager.getActiveSubscriptionInfoList()).thenReturn(subList);
        when(mSubscriptionManager.getActiveSubscriptionIdList())
                .thenReturn(new int[]{DATA_SUBID});

        WifiCarrierInfoManager tu = new WifiCarrierInfoManager(mTelephonyManager,
                mSubscriptionManager, mWifiInjector, mock(FrameworkFacade.class),
                mock(WifiContext.class), mock(WifiConfigStore.class), mock(Handler.class),
                mWifiMetrics);
        mWifiCarrierInfoManager = spy(tu);
        // static mocking
        mSession = ExtendedMockito.mockitoSession().strictness(Strictness.LENIENT)
                .spyStatic(MacAddress.class)
                .startMocking();
        initializeCmi();

        mOsuProvider = PasspointProvisioningTestUtil.generateOsuProvider(true);
        mConnectedNetwork = spy(WifiConfigurationTestUtil.createOpenNetwork());
        when(mNullAsyncChannel.sendMessageSynchronously(any())).thenReturn(null);
        when(mWifiScoreCard.getL2KeyAndGroupHint(any())).thenReturn(new Pair<>(null, null));
        when(mDeviceConfigFacade.isAbnormalDisconnectionBugreportEnabled()).thenReturn(true);
        when(mDeviceConfigFacade.isAbnormalConnectionFailureBugreportEnabled()).thenReturn(true);
        when(mDeviceConfigFacade.isOverlappingConnectionBugreportEnabled()).thenReturn(true);
        when(mDeviceConfigFacade.getOverlappingConnectionDurationThresholdMs()).thenReturn(
                DeviceConfigFacade.DEFAULT_OVERLAPPING_CONNECTION_DURATION_THRESHOLD_MS);
        when(mWifiScoreCard.detectAbnormalConnectionFailure(anyString()))
                .thenReturn(WifiHealthMonitor.REASON_NO_FAILURE);
        when(mWifiScoreCard.detectAbnormalDisconnection())
                .thenReturn(WifiHealthMonitor.REASON_NO_FAILURE);
        when(mThroughputPredictor.predictMaxTxThroughput(any())).thenReturn(90);
        when(mThroughputPredictor.predictMaxRxThroughput(any())).thenReturn(80);
    }

    private void registerAsyncChannel(Consumer<AsyncChannel> consumer, Messenger messenger,
            Handler wrappedHandler) {
        final AsyncChannel channel = new AsyncChannel();
        Handler handler = new Handler(mLooper.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            consumer.accept(channel);
                        } else {
                            Log.d(TAG, "Failed to connect Command channel " + this);
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        Log.d(TAG, "Command channel disconnected " + this);
                        break;
                    case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                        Log.d(TAG, "Command channel fully connected " + this);
                        break;
                    default:
                        if (wrappedHandler != null) {
                            wrappedHandler.handleMessage(msg);
                        }
                        break;
                }
            }
        };

        channel.connect(mContext, handler, messenger);
        mLooper.dispatchAll();
    }

    private void registerAsyncChannel(Consumer<AsyncChannel> consumer, Messenger messenger) {
        registerAsyncChannel(consumer, messenger, null /* wrappedHandler */);
    }

    private void initializeCmi() throws Exception {
        mCmi = new ClientModeImpl(mContext, mWifiMetrics, mClock, mWifiScoreCard, mWifiStateTracker,
                mWifiPermissionsUtil, mWifiConfigManager, mPasspointManager,
                mWifiMonitor, mWifiDiagnostics, mWifiDataStall,
                new ScoringParams(), new WifiThreadRunner(new Handler(mLooper.getLooper())),
                mWifiNetworkSuggestionsManager, mWifiHealthMonitor, mThroughputPredictor,
                mDeviceConfigFacade, mScanRequestProxy, mWifiInfo, mWifiConnectivityManager,
                mWifiBlocklistMonitor, mConnectionFailureNotifier,
                WifiInjector.NETWORK_CAPABILITIES_FILTER, mWifiNetworkFactory,
                mUntrustedWifiNetworkFactory, mOemPaidWifiNetworkFactory,
                mOemPrivateWifiNetworkFactory, mWifiLastResortWatchdog, mWakeupController,
                mWifiLockManager, mFrameworkFacade, mLooper.getLooper(),
                mCountryCode, mWifiNative,
                mWrongPasswordNotifier, mWifiTrafficPoller, mLinkProbeManager,
                1, mBatteryStatsManager, mSupplicantStateTracker, mMboOceController,
                mWifiCarrierInfoManager, mEapFailureNotifier, mSimRequiredNotifier,
                mWifiScoreReport, mWifiP2pConnection, mWifiGlobals,
                WIFI_IFACE_NAME, mClientModeManager, mCmiMonitor, false);

        mWifiCoreThread = getCmiHandlerThread(mCmi);

        mBinderToken = Binder.clearCallingIdentity();

        verify(mWifiConfigManager, atLeastOnce()).addOnNetworkUpdateListener(
                mConfigUpdateListenerCaptor.capture());
        assertNotNull(mConfigUpdateListenerCaptor.getValue());

        mCmi.enableVerboseLogging(true);
        mLooper.dispatchAll();

        verify(mWifiLastResortWatchdog, atLeastOnce()).clearAllFailureCounts();
        // set ready for country code changes at initialization.
        verify(mCountryCode, atLeastOnce()).setReadyForChange(true);
        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    @After
    public void cleanUp() throws Exception {
        Binder.restoreCallingIdentity(mBinderToken);

        if (mSyncThread != null) stopLooper(mSyncThread.getLooper());
        if (mWifiCoreThread != null) stopLooper(mWifiCoreThread.getLooper());
        if (mP2pThread != null) stopLooper(mP2pThread.getLooper());

        mWifiCoreThread = null;
        mP2pThread = null;
        mSyncThread = null;
        mCmi = null;
        mSession.finishMocking();
    }

    /**
     *  Test that mode changes accurately reflect the value for isWifiEnabled.
     */
    @Test
    public void checkIsWifiEnabledForModeChanges() throws Exception {
        // now disable client mode and verify the reported wifi state
        mCmi.stop();
        mLooper.dispatchAll();
        verify(mContext, never())
                .sendStickyBroadcastAsUser(argThat(new WifiEnablingStateIntentMatcher()), any());
    }

    private static class WifiEnablingStateIntentMatcher implements ArgumentMatcher<Intent> {
        @Override
        public boolean matches(Intent intent) {
            return WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())
                    && WifiManager.WIFI_STATE_ENABLING
                            == intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                    WifiManager.WIFI_STATE_DISABLED);
        }
    }

    private class NetworkStateChangedIntentMatcher implements ArgumentMatcher<Intent> {
        private final NetworkInfo.DetailedState mState;
        NetworkStateChangedIntentMatcher(NetworkInfo.DetailedState state) {
            mState = state;
        }
        @Override
        public boolean matches(Intent intent) {
            if (WifiManager.NETWORK_STATE_CHANGED_ACTION != intent.getAction()) {
                // not the correct type
                return false;
            }
            NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            return networkInfo.getDetailedState() == mState;
        }
    }

    private void canSaveNetworkConfig() throws Exception {
        IActionListener connectActionListener = mock(IActionListener.class);
        mCmi.saveNetwork(
                new NetworkUpdateResult(TEST_NETWORK_ID),
                new ActionListenerWrapper(connectActionListener),
                Binder.getCallingUid());
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();
    }

    /**
     * Verifies that configs can be saved when in client mode.
     */
    @Test
    public void canSaveNetworkConfigInClientMode() throws Exception {
        canSaveNetworkConfig();
    }

    private WifiConfiguration createTestNetwork(boolean isHidden) {
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = FRAMEWORK_NETWORK_ID;
        config.SSID = sSSID;
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.hiddenSSID = isHidden;
        return config;
    }

    private void initializeMocksForAddedNetwork(boolean isHidden) throws Exception {
        WifiConfiguration config = createTestNetwork(isHidden);

        when(mWifiConfigManager.getSavedNetworks(anyInt())).thenReturn(Arrays.asList(config));
        when(mWifiConfigManager.getConfiguredNetwork(0)).thenReturn(config);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(0)).thenReturn(config);
    }

    private void initializeAndAddNetworkAndVerifySuccess() throws Exception {
        initializeAndAddNetworkAndVerifySuccess(false);
    }

    private void initializeAndAddNetworkAndVerifySuccess(boolean isHidden) throws Exception {
        initializeMocksForAddedNetwork(isHidden);
    }

    private void setupAndStartConnectSequence(WifiConfiguration config) throws Exception {
        when(mWifiConfigManager.getConfiguredNetwork(eq(config.networkId)))
                .thenReturn(config);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(
                eq(config.networkId))).thenReturn(config);

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        IActionListener connectActionListener = mock(IActionListener.class);
        mCmi.connectNetwork(
                new NetworkUpdateResult(config.networkId),
                new ActionListenerWrapper(connectActionListener),
                Binder.getCallingUid());
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();
    }

    private void validateSuccessfulConnectSequence(WifiConfiguration config) {
        verify(mWifiConnectivityManager).prepareForForcedConnection(eq(config.networkId));
        verify(mWifiConfigManager).getConfiguredNetworkWithoutMasking(eq(config.networkId));
        verify(mWifiNative).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
        verify(mCountryCode, atLeastOnce()).setReadyForChange(false);
        assertEquals("L2ConnectingState", mCmi.getCurrentState().getName());
    }

    private void validateFailureConnectSequence(WifiConfiguration config) {
        verify(mWifiConnectivityManager).prepareForForcedConnection(eq(config.networkId));
        verify(mWifiConfigManager, never())
                .getConfiguredNetworkWithoutMasking(eq(config.networkId));
        verify(mWifiNative, never()).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
    }

    /**
     * Tests the network connection initiation sequence with the default network request pending
     * from WifiNetworkFactory.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnect() throws Exception {
        WifiConfiguration config = mConnectedNetwork;
        config.networkId = FRAMEWORK_NETWORK_ID;
        config.setRandomizedMacAddress(TEST_LOCAL_MAC_ADDRESS);
        config.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_AUTO;
        config.getNetworkSelectionStatus().setHasEverConnected(mTestNetworkParams.hasEverConnected);
        setupAndStartConnectSequence(config);
        validateSuccessfulConnectSequence(config);
    }

    /**
     * Tests the network connection initiation sequence with the default network request pending
     * from WifiNetworkFactory.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnectFromNonSettingsApp() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID;
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(Process.myUid()))
                .thenReturn(false);
        setupAndStartConnectSequence(config);
        verify(mWifiConnectivityManager).prepareForForcedConnection(eq(config.networkId));
        verify(mWifiConfigManager).getConfiguredNetworkWithoutMasking(eq(config.networkId));
        verify(mWifiNative).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
    }

    /**
     * Tests the network connection initiation sequence with no network request pending from
     * from WifiNetworkFactory.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we don't invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnectWithNoNetworkRequest() throws Exception {
        // Remove the network requests.
        when(mWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mUntrustedWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mOemPaidWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mOemPrivateWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID;
        setupAndStartConnectSequence(config);
        validateFailureConnectSequence(config);
    }

    /**
     * Tests the entire successful network connection flow.
     */
    @Test
    public void connect() throws Exception {
        assertNull(mCmi.getConnectingWifiConfiguration());
        assertNull(mCmi.getConnectedWifiConfiguration());

        triggerConnect();

        assertNotNull(mCmi.getConnectingWifiConfiguration());
        assertNull(mCmi.getConnectedWifiConfiguration());

        WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID);
        config.carrierId = CARRIER_ID_1;
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);

        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.ASSOCIATED));
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(mWifiMetrics).noteFirstL2ConnectionAfterBoot(true);

        // L2 connected, but not L3 connected yet. So, still "Connecting"...
        assertNotNull(mCmi.getConnectingWifiConfiguration());
        assertNull(mCmi.getConnectedWifiConfiguration());

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("L3ProvisioningState", getCurrentState().getName());
        verify(mContext).sendStickyBroadcastAsUser(
                argThat(new NetworkStateChangedIntentMatcher(CONNECTING)), any());
        verify(mContext).sendStickyBroadcastAsUser(
                argThat(new NetworkStateChangedIntentMatcher(OBTAINING_IPADDR)), any());

        DhcpResultsParcelable dhcpResults = new DhcpResultsParcelable();
        dhcpResults.baseConfiguration = new StaticIpConfiguration();
        dhcpResults.baseConfiguration.gateway = InetAddresses.parseNumericAddress("1.2.3.4");
        dhcpResults.baseConfiguration.ipAddress =
                new LinkAddress(InetAddresses.parseNumericAddress("192.168.1.100"), 0);
        dhcpResults.baseConfiguration.dnsServers.add(InetAddresses.parseNumericAddress("8.8.8.8"));
        dhcpResults.leaseDuration = 3600;

        injectDhcpSuccess(dhcpResults);
        mLooper.dispatchAll();

        assertNull(mCmi.getConnectingWifiConfiguration());
        assertNotNull(mCmi.getConnectedWifiConfiguration());

        // Verify WifiMetrics logging for metered metrics based on DHCP results
        verify(mWifiMetrics).addMeteredStat(any(), anyBoolean());
        WifiInfo wifiInfo = mWifiInfo;
        assertNotNull(wifiInfo);
        assertEquals(sBSSID, wifiInfo.getBSSID());
        assertEquals(sFreq, wifiInfo.getFrequency());
        assertTrue(sWifiSsid.equals(wifiInfo.getWifiSsid()));
        assertNull(wifiInfo.getPasspointProviderFriendlyName());
        expectRegisterNetworkAgent((na) -> {
        }, (nc) -> {
                if (SdkLevel.isAtLeastS()) {
                    WifiInfo wifiInfoFromTi = (WifiInfo) nc.getTransportInfo();
                    assertEquals(sBSSID, wifiInfoFromTi.getBSSID());
                    assertEquals(sFreq, wifiInfoFromTi.getFrequency());
                    assertTrue(sWifiSsid.equals(wifiInfoFromTi.getWifiSsid()));
                    assertNull(wifiInfoFromTi.getPasspointProviderFriendlyName());
                }
            });
        // Ensure the connection stats for the network is updated.
        verify(mWifiConfigManager).updateNetworkAfterConnect(eq(FRAMEWORK_NETWORK_ID),
                anyBoolean(), anyInt());
        verify(mWifiConfigManager).updateRandomizedMacExpireTime(any(), anyLong());
        verify(mContext).sendStickyBroadcastAsUser(
                argThat(new NetworkStateChangedIntentMatcher(CONNECTED)), any());

        // Anonymous Identity is not set.
        assertEquals("", mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());
        verify(mWifiStateTracker).updateState(eq(WifiStateTracker.CONNECTED));
        assertEquals("L3ConnectedState", getCurrentState().getName());
        verify(mWifiMetrics).incrementNumOfCarrierWifiConnectionSuccess();
        verify(mWifiLockManager).updateWifiClientConnected(mClientModeManager, true);
        verify(mWifiNative).getConnectionCapabilities(any());
        verify(mThroughputPredictor).predictMaxTxThroughput(any());
        verify(mWifiMetrics).setConnectionMaxSupportedLinkSpeedMbps(WIFI_IFACE_NAME, 90, 80);
        verify(mWifiDataStall).setConnectionCapabilities(any());
        assertEquals(90, wifiInfo.getMaxSupportedTxLinkSpeedMbps());
        verify(mWifiMetrics).noteFirstL3ConnectionAfterBoot(true);
    }

    private void setupEapSimConnection() throws Exception {
        mConnectedNetwork = spy(WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE));
        mConnectedNetwork.carrierId = CARRIER_ID_1;
        doReturn(DATA_SUBID).when(mWifiCarrierInfoManager)
                .getBestMatchSubscriptionId(any(WifiConfiguration.class));
        when(mDataTelephonyManager.getSimOperator()).thenReturn("123456");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        mConnectedNetwork.enterpriseConfig.setAnonymousIdentity("");

        triggerConnect();

        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        assertEquals("L3ProvisioningState", getCurrentState().getName());
    }

    /**
     * When the SIM card was removed, if the current wifi connection is not using it, the connection
     * should be kept.
     */
    @Test
    public void testResetSimWhenNonConnectedSimRemoved() throws Exception {
        setupEapSimConnection();
        doReturn(true).when(mWifiCarrierInfoManager).isSimPresent(eq(DATA_SUBID));
        mCmi.sendMessage(ClientModeImpl.CMD_RESET_SIM_NETWORKS,
                ClientModeImpl.RESET_SIM_REASON_SIM_REMOVED);
        mLooper.dispatchAll();

        verify(mSimRequiredNotifier, never()).showSimRequiredNotification(any(), any());
        assertEquals("L3ProvisioningState", getCurrentState().getName());
    }

    /**
     * When the SIM card was removed, if the current wifi connection is using it, the connection
     * should be disconnected, otherwise, the connection shouldn't be impacted.
     */
    @Test
    public void testResetSimWhenConnectedSimRemoved() throws Exception {
        setupEapSimConnection();
        doReturn(false).when(mWifiCarrierInfoManager).isSimPresent(eq(DATA_SUBID));
        mCmi.sendMessage(ClientModeImpl.CMD_RESET_SIM_NETWORKS,
                ClientModeImpl.RESET_SIM_REASON_SIM_REMOVED);
        mLooper.dispatchAll();

        verify(mSimRequiredNotifier).showSimRequiredNotification(any(), any());
        verify(mWifiNative, times(2)).removeAllNetworks(WIFI_IFACE_NAME);
    }

    /**
     * When the SIM card was removed, if the current wifi connection is using it, the connection
     * should be disconnected, otherwise, the connection shouldn't be impacted.
     */
    @Test
    public void testResetSimWhenConnectedSimRemovedAfterNetworkRemoval() throws Exception {
        setupEapSimConnection();
        doReturn(false).when(mWifiCarrierInfoManager).isSimPresent(eq(DATA_SUBID));
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(null);
        mCmi.sendMessage(ClientModeImpl.CMD_RESET_SIM_NETWORKS,
                ClientModeImpl.RESET_SIM_REASON_SIM_REMOVED);
        mLooper.dispatchAll();

        verify(mSimRequiredNotifier, never()).showSimRequiredNotification(any(), any());
        assertEquals("L3ProvisioningState", getCurrentState().getName());
    }

    /**
     * When the default data SIM is changed, if the current wifi connection is carrier wifi,
     * the connection should be disconnected.
     */
    @Test
    public void testResetSimWhenDefaultDataSimChanged() throws Exception {
        setupEapSimConnection();
        mCmi.sendMessage(ClientModeImpl.CMD_RESET_SIM_NETWORKS,
                ClientModeImpl.RESET_SIM_REASON_DEFAULT_DATA_SIM_CHANGED);
        mLooper.dispatchAll();

        verify(mWifiNative, times(2)).removeAllNetworks(WIFI_IFACE_NAME);
    }

    /**
     * Tests anonymous identity is set again whenever a connection is established for the carrier
     * that supports encrypted IMSI and anonymous identity and no real pseudonym was provided.
     */
    @Test
    public void testSetAnonymousIdentityWhenConnectionIsEstablishedNoPseudonym() throws Exception {
        mConnectedNetwork = spy(WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE));
        when(mDataTelephonyManager.getSimOperator()).thenReturn("123456");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        mConnectedNetwork.enterpriseConfig.setAnonymousIdentity("");

        String expectedAnonymousIdentity = "anonymous@wlan.mnc456.mcc123.3gppnetwork.org";
        MockitoSession mockSession = ExtendedMockito.mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        when(SubscriptionManager.getDefaultDataSubscriptionId()).thenReturn(DATA_SUBID);
        doReturn(true).when(mWifiCarrierInfoManager).isImsiEncryptionInfoAvailable(anyInt());

        // Initial value should be "not set"
        assertEquals("", mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());

        triggerConnect();

        // CMD_START_CONNECT should have set anonymousIdentity to anonymous@<realm>
        assertEquals(expectedAnonymousIdentity,
                mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());

        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());
        when(mWifiNative.getEapAnonymousIdentity(anyString()))
                .thenReturn(expectedAnonymousIdentity);

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(mWifiNative).getEapAnonymousIdentity(any());

        // Post connection value should remain "not set"
        assertEquals("", mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());
        // verify that WifiConfigManager#addOrUpdateNetwork() was called to clear any previously
        // stored pseudonym. i.e. to enable Encrypted IMSI for subsequent connections.
        // Note: This test will fail if future logic will have additional conditions that would
        // trigger "add or update network" operation. The test needs to be updated to account for
        // this change.
        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt());
        mockSession.finishMocking();
    }

    /**
     * Tests anonymous identity is set again whenever a connection is established for the carrier
     * that supports encrypted IMSI and anonymous identity but real pseudonym was provided for
     * subsequent connections.
     */
    @Test
    public void testSetAnonymousIdentityWhenConnectionIsEstablishedWithPseudonym()
            throws Exception {
        mConnectedNetwork = spy(WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE));
        when(mDataTelephonyManager.getSimOperator()).thenReturn("123456");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        mConnectedNetwork.enterpriseConfig.setAnonymousIdentity("");

        String expectedAnonymousIdentity = "anonymous@wlan.mnc456.mcc123.3gppnetwork.org";
        String pseudonym = "83bcca9384fca@wlan.mnc456.mcc123.3gppnetwork.org";
        MockitoSession mockSession = ExtendedMockito.mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        when(SubscriptionManager.getDefaultDataSubscriptionId()).thenReturn(DATA_SUBID);
        doReturn(true).when(mWifiCarrierInfoManager).isImsiEncryptionInfoAvailable(anyInt());

        triggerConnect();

        // CMD_START_CONNECT should have set anonymousIdentity to anonymous@<realm>
        assertEquals(expectedAnonymousIdentity,
                mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());

        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());
        when(mWifiNative.getEapAnonymousIdentity(anyString()))
                .thenReturn(pseudonym);

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(mWifiNative).getEapAnonymousIdentity(any());
        assertEquals(pseudonym,
                mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());
        // Verify that WifiConfigManager#addOrUpdateNetwork() was called if there we received a
        // real pseudonym to be stored. i.e. Encrypted IMSI will be used once, followed by
        // pseudonym usage in all subsequent connections.
        // Note: This test will fail if future logic will have additional conditions that would
        // trigger "add or update network" operation. The test needs to be updated to account for
        // this change.
        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt());
        mockSession.finishMocking();
    }

    /**
     * Tests anonymous identity is set again whenever a connection is established for the carrier
     * that supports encrypted IMSI and anonymous identity but real but not decorated pseudonym was
     * provided for subsequent connections.
     */
    @Test
    public void testSetAnonymousIdentityWhenConnectionIsEstablishedWithNonDecoratedPseudonym()
            throws Exception {
        mConnectedNetwork = spy(WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE));
        when(mDataTelephonyManager.getSimOperator()).thenReturn("123456");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        mConnectedNetwork.enterpriseConfig.setAnonymousIdentity("");

        String realm = "wlan.mnc456.mcc123.3gppnetwork.org";
        String expectedAnonymousIdentity = "anonymous";
        String pseudonym = "83bcca9384fca";
        MockitoSession mockSession = ExtendedMockito.mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        when(SubscriptionManager.getDefaultDataSubscriptionId()).thenReturn(DATA_SUBID);
        doReturn(true).when(mWifiCarrierInfoManager).isImsiEncryptionInfoAvailable(anyInt());

        triggerConnect();

        // CMD_START_CONNECT should have set anonymousIdentity to anonymous@<realm>
        assertEquals(expectedAnonymousIdentity + "@" + realm,
                mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());

        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());
        when(mWifiNative.getEapAnonymousIdentity(anyString()))
                .thenReturn(pseudonym);

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(mWifiNative).getEapAnonymousIdentity(any());
        assertEquals(pseudonym + "@" + realm,
                mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());
        // Verify that WifiConfigManager#addOrUpdateNetwork() was called if there we received a
        // real pseudonym to be stored. i.e. Encrypted IMSI will be used once, followed by
        // pseudonym usage in all subsequent connections.
        // Note: This test will fail if future logic will have additional conditions that would
        // trigger "add or update network" operation. The test needs to be updated to account for
        // this change.
        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt());
        mockSession.finishMocking();
    }

    /**
     * Tests anonymous identity will be set to suggestion network.
     */
    @Test
    public void testSetAnonymousIdentityWhenConnectionIsEstablishedWithPseudonymForSuggestion()
            throws Exception {
        mConnectedNetwork = spy(WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE));
        when(mDataTelephonyManager.getSimOperator()).thenReturn("123456");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        mConnectedNetwork.enterpriseConfig.setAnonymousIdentity("");
        mConnectedNetwork.fromWifiNetworkSuggestion = true;

        String expectedAnonymousIdentity = "anonymous@wlan.mnc456.mcc123.3gppnetwork.org";
        String pseudonym = "83bcca9384fca@wlan.mnc456.mcc123.3gppnetwork.org";
        MockitoSession mockSession = ExtendedMockito.mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        when(SubscriptionManager.getDefaultDataSubscriptionId()).thenReturn(DATA_SUBID);
        doReturn(true).when(mWifiCarrierInfoManager).isImsiEncryptionInfoAvailable(anyInt());

        triggerConnect();

        // CMD_START_CONNECT should have set anonymousIdentity to anonymous@<realm>
        assertEquals(expectedAnonymousIdentity,
                mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());

        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());
        when(mWifiNative.getEapAnonymousIdentity(anyString()))
                .thenReturn(pseudonym);

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(mWifiNative).getEapAnonymousIdentity(any());
        assertEquals(pseudonym,
                mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());
        // Verify that WifiConfigManager#addOrUpdateNetwork() was called if there we received a
        // real pseudonym to be stored. i.e. Encrypted IMSI will be used once, followed by
        // pseudonym usage in all subsequent connections.
        // Note: This test will fail if future logic will have additional conditions that would
        // trigger "add or update network" operation. The test needs to be updated to account for
        // this change.
        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt());
        verify(mWifiNetworkSuggestionsManager).setAnonymousIdentity(any());
        mockSession.finishMocking();
    }

    /**
     * Tests anonymous identity will be set to passpoint network.
     */
    @Test
    public void testSetAnonymousIdentityWhenConnectionIsEstablishedWithPseudonymForPasspoint()
            throws Exception {
        mConnectedNetwork = spy(WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE));
        when(mDataTelephonyManager.getSimOperator()).thenReturn("123456");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        mConnectedNetwork.enterpriseConfig.setAnonymousIdentity("");
        mConnectedNetwork.FQDN = WifiConfigurationTestUtil.TEST_FQDN;
        mConnectedNetwork.providerFriendlyName =
                WifiConfigurationTestUtil.TEST_PROVIDER_FRIENDLY_NAME;
        mConnectedNetwork.setPasspointUniqueId(WifiConfigurationTestUtil.TEST_FQDN + "_"
                + WifiConfigurationTestUtil.TEST_FQDN.hashCode());


        String expectedAnonymousIdentity = "anonymous@wlan.mnc456.mcc123.3gppnetwork.org";
        String pseudonym = "83bcca9384fca@wlan.mnc456.mcc123.3gppnetwork.org";
        MockitoSession mockSession = ExtendedMockito.mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        when(SubscriptionManager.getDefaultDataSubscriptionId()).thenReturn(DATA_SUBID);
        doReturn(true).when(mWifiCarrierInfoManager).isImsiEncryptionInfoAvailable(anyInt());

        triggerConnect();

        // CMD_START_CONNECT should have set anonymousIdentity to anonymous@<realm>
        assertEquals(expectedAnonymousIdentity,
                mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());

        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());
        when(mWifiNative.getEapAnonymousIdentity(anyString()))
                .thenReturn(pseudonym);

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(mWifiNative).getEapAnonymousIdentity(any());
        assertEquals(pseudonym,
                mConnectedNetwork.enterpriseConfig.getAnonymousIdentity());
        // Verify that WifiConfigManager#addOrUpdateNetwork() was called if there we received a
        // real pseudonym to be stored. i.e. Encrypted IMSI will be used once, followed by
        // pseudonym usage in all subsequent connections.
        // Note: This test will fail if future logic will have additional conditions that would
        // trigger "add or update network" operation. The test needs to be updated to account for
        // this change.
        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt());
        verify(mPasspointManager).setAnonymousIdentity(any());
        mockSession.finishMocking();
    }
    /**
     * Tests the Passpoint information is set in WifiInfo for Passpoint AP connection.
     */
    @Test
    public void connectPasspointAp() throws Exception {
        WifiConfiguration config = spy(WifiConfigurationTestUtil.createPasspointNetwork());
        config.SSID = sWifiSsid.toString();
        config.BSSID = sBSSID;
        config.networkId = FRAMEWORK_NETWORK_ID;
        config.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_AUTO;
        setupAndStartConnectSequence(config);
        validateSuccessfulConnectSequence(config);

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(FRAMEWORK_NETWORK_ID, sWifiSsid, sBSSID,
                        SupplicantState.ASSOCIATING));
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mWifiInfo;
        assertNotNull(wifiInfo);
        assertEquals(WifiConfigurationTestUtil.TEST_FQDN, wifiInfo.getPasspointFqdn());
        assertEquals(WifiConfigurationTestUtil.TEST_PROVIDER_FRIENDLY_NAME,
                wifiInfo.getPasspointProviderFriendlyName());
    }

    /**
     * Tests that Passpoint fields in WifiInfo are reset when connecting to a non-Passpoint network
     * during DisconnectedState.
     * @throws Exception
     */
    @Test
    public void testResetWifiInfoPasspointFields() throws Exception {
        WifiConfiguration config = spy(WifiConfigurationTestUtil.createPasspointNetwork());
        config.SSID = sWifiSsid.toString();
        config.BSSID = sBSSID;
        config.networkId = PASSPOINT_NETWORK_ID;
        config.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_AUTO;
        setupAndStartConnectSequence(config);
        validateSuccessfulConnectSequence(config);

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(PASSPOINT_NETWORK_ID, sWifiSsid, sBSSID,
                        SupplicantState.ASSOCIATING));
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(FRAMEWORK_NETWORK_ID, sWifiSsid, sBSSID,
                        SupplicantState.ASSOCIATING));
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mWifiInfo;
        assertNotNull(wifiInfo);
        assertNull(wifiInfo.getPasspointFqdn());
        assertNull(wifiInfo.getPasspointProviderFriendlyName());
    }

    /**
     * Tests the OSU information is set in WifiInfo for OSU AP connection.
     */
    @Test
    public void connectOsuAp() throws Exception {
        WifiConfiguration osuConfig = spy(WifiConfigurationTestUtil.createEphemeralNetwork());
        osuConfig.SSID = sWifiSsid.toString();
        osuConfig.BSSID = sBSSID;
        osuConfig.osu = true;
        osuConfig.networkId = FRAMEWORK_NETWORK_ID;
        osuConfig.providerFriendlyName = WifiConfigurationTestUtil.TEST_PROVIDER_FRIENDLY_NAME;
        osuConfig.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_AUTO;
        setupAndStartConnectSequence(osuConfig);
        validateSuccessfulConnectSequence(osuConfig);

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(FRAMEWORK_NETWORK_ID, sWifiSsid, sBSSID,
                        SupplicantState.ASSOCIATING));
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mWifiInfo;
        assertNotNull(wifiInfo);
        assertTrue(wifiInfo.isOsuAp());
        assertEquals(WifiConfigurationTestUtil.TEST_PROVIDER_FRIENDLY_NAME,
                wifiInfo.getPasspointProviderFriendlyName());
    }

    /**
     * Tests that OSU fields in WifiInfo are reset when connecting to a non-OSU network during
     * DisconnectedState.
     * @throws Exception
     */
    @Test
    public void testResetWifiInfoOsuFields() throws Exception {
        WifiConfiguration osuConfig = spy(WifiConfigurationTestUtil.createEphemeralNetwork());
        osuConfig.SSID = sWifiSsid.toString();
        osuConfig.BSSID = sBSSID;
        osuConfig.osu = true;
        osuConfig.networkId = PASSPOINT_NETWORK_ID;
        osuConfig.providerFriendlyName = WifiConfigurationTestUtil.TEST_PROVIDER_FRIENDLY_NAME;
        osuConfig.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_AUTO;
        setupAndStartConnectSequence(osuConfig);
        validateSuccessfulConnectSequence(osuConfig);

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(PASSPOINT_NETWORK_ID, sWifiSsid, sBSSID,
                        SupplicantState.ASSOCIATING));
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(FRAMEWORK_NETWORK_ID, sWifiSsid, sBSSID,
                        SupplicantState.ASSOCIATING));
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mWifiInfo;
        assertNotNull(wifiInfo);
        assertFalse(wifiInfo.isOsuAp());
    }

    /**
     * Verify that WifiStateTracker is called if wifi is disabled while connected.
     */
    @Test
    public void verifyWifiStateTrackerUpdatedWhenDisabled() throws Exception {
        connect();

        mCmi.stop();
        mLooper.dispatchAll();
        verify(mWifiStateTracker).updateState(eq(WifiStateTracker.DISCONNECTED));
    }

    /**
     * Tests the network connection initiation sequence with no network request pending from
     * from WifiNetworkFactory when we're already connected to a different network.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnectWithNoNetworkRequestAndAlreadyConnected() throws Exception {
        // Simulate the first connection.
        connect();

        // Remove the network requests.
        when(mWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mUntrustedWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mOemPaidWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mOemPrivateWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID + 1;
        setupAndStartConnectSequence(config);
        validateSuccessfulConnectSequence(config);
        verify(mWifiPermissionsUtil, atLeastOnce()).checkNetworkSettingsPermission(anyInt());
    }

    /**
     * Tests the network connection initiation sequence from a non-privileged app with no network
     * request pending from from WifiNetworkFactory when we're already connected to a different
     * network.
     * This simulates the connect sequence using the public
     * {@link WifiManager#enableNetwork(int, boolean)} and ensures that we don't invoke
     * {@link WifiNative#connectToNetwork(WifiConfiguration)}.
     */
    @Test
    public void triggerConnectWithNoNetworkRequestAndAlreadyConnectedButNonPrivilegedApp()
            throws Exception {
        // Simulate the first connection.
        connect();

        // Remove the network requests.
        when(mWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mUntrustedWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mOemPaidWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);
        when(mOemPrivateWifiNetworkFactory.hasConnectionRequests()).thenReturn(false);

        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(false);

        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID + 1;
        setupAndStartConnectSequence(config);
        verify(mWifiConnectivityManager).prepareForForcedConnection(eq(config.networkId));
        verify(mWifiConfigManager, never())
                .getConfiguredNetworkWithoutMasking(eq(config.networkId));
        verify(mWifiNative, never()).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
        verify(mWifiPermissionsUtil, times(2)).checkNetworkSettingsPermission(anyInt());
    }

    /**
     * If caller tries to connect to a network that is already connected, the connection request
     * should succeed.
     *
     * Test: Create and connect to a network, then try to reconnect to the same network. Verify
     * that connection request returns with CONNECT_NETWORK_SUCCEEDED.
     */
    @Test
    public void reconnectToConnectedNetworkWithNetworkId() throws Exception {
        connect();

        // try to reconnect
        IActionListener connectActionListener = mock(IActionListener.class);
        mCmi.connectNetwork(
                new NetworkUpdateResult(FRAMEWORK_NETWORK_ID),
                new ActionListenerWrapper(connectActionListener),
                Binder.getCallingUid());
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();

        // Verify that we didn't trigger a second connection.
        verify(mWifiNative, times(1)).connectToNetwork(eq(WIFI_IFACE_NAME), any());
    }

    /**
     * If caller tries to connect to a network that is already connected, the connection request
     * should succeed.
     *
     * Test: Create and connect to a network, then try to reconnect to the same network. Verify
     * that connection request returns with CONNECT_NETWORK_SUCCEEDED.
     */
    @Test
    public void reconnectToConnectedNetworkWithConfig() throws Exception {
        connect();

        // try to reconnect
        IActionListener connectActionListener = mock(IActionListener.class);
        int callingUid = Binder.getCallingUid();
        mCmi.connectNetwork(
                new NetworkUpdateResult(FRAMEWORK_NETWORK_ID),
                new ActionListenerWrapper(connectActionListener),
                callingUid);
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();

        // Verify that we didn't trigger a second connection.
        verify(mWifiNative, times(1)).connectToNetwork(eq(WIFI_IFACE_NAME), any());
    }

    /**
     * If caller tries to connect to a network that is already connecting, the connection request
     * should succeed.
     *
     * Test: Create and trigger connect to a network, then try to reconnect to the same network.
     * Verify that connection request returns with CONNECT_NETWORK_SUCCEEDED and did not trigger a
     * new connection.
     */
    @Test
    public void reconnectToConnectingNetwork() throws Exception {
        triggerConnect();

        // try to reconnect to the same network (before connection is established).
        IActionListener connectActionListener = mock(IActionListener.class);
        mCmi.connectNetwork(
                new NetworkUpdateResult(FRAMEWORK_NETWORK_ID),
                new ActionListenerWrapper(connectActionListener),
                Binder.getCallingUid());
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();

        // Verify that we didn't trigger a second connection.
        verify(mWifiNative, times(1)).connectToNetwork(eq(WIFI_IFACE_NAME), any());
    }

    /**
     * If caller tries to connect to a network that is already connecting, the connection request
     * should succeed.
     *
     * Test: Create and trigger connect to a network, then try to reconnect to the same network.
     * Verify that connection request returns with CONNECT_NETWORK_SUCCEEDED and did trigger a new
     * connection.
     */
    @Test
    public void reconnectToConnectingNetworkWithCredentialChange() throws Exception {
        triggerConnect();

        // try to reconnect to the same network with a credential changed (before connection is
        // established).
        NetworkUpdateResult networkUpdateResult = new NetworkUpdateResult(
                FRAMEWORK_NETWORK_ID,
                false /* ip */,
                false /* proxy */,
                true /* credential */,
                false /* isNewNetwork */);
        IActionListener connectActionListener = mock(IActionListener.class);
        mCmi.connectNetwork(
                networkUpdateResult,
                new ActionListenerWrapper(connectActionListener),
                Binder.getCallingUid());
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();

        // Verify that we triggered a second connection.
        verify(mWifiNative, times(2)).connectToNetwork(eq(WIFI_IFACE_NAME), any());
    }

    /**
     * If caller tries to connect to a network that previously failed connection, the connection
     * request should succeed.
     *
     * Test: Create and trigger connect to a network, then fail the connection. Now try to reconnect
     * to the same network. Verify that connection request returns with CONNECT_NETWORK_SUCCEEDED
     * and did trigger a new * connection.
     */
    @Test
    public void connectAfterAssociationRejection() throws Exception {
        triggerConnect();

        // fail the connection.
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(mConnectedNetwork.SSID, sBSSID,
                        ISupplicantStaIfaceCallback.StatusCode.AP_UNABLE_TO_HANDLE_NEW_STA, false));
        mLooper.dispatchAll();

        IActionListener connectActionListener = mock(IActionListener.class);
        mCmi.connectNetwork(
                new NetworkUpdateResult(FRAMEWORK_NETWORK_ID),
                new ActionListenerWrapper(connectActionListener),
                Binder.getCallingUid());
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();

        // Verify that we triggered a second connection.
        verify(mWifiNative, times(2)).connectToNetwork(eq(WIFI_IFACE_NAME), any());
    }

    /**
     * If caller tries to connect to a network that previously failed connection, the connection
     * request should succeed.
     *
     * Test: Create and trigger connect to a network, then fail the connection. Now try to reconnect
     * to the same network. Verify that connection request returns with CONNECT_NETWORK_SUCCEEDED
     * and did trigger a new * connection.
     */
    @Test
    public void connectAfterConnectionFailure() throws Exception {
        triggerConnect();

        // fail the connection.
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(mConnectedNetwork.SSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        IActionListener connectActionListener = mock(IActionListener.class);
        mCmi.connectNetwork(
                new NetworkUpdateResult(FRAMEWORK_NETWORK_ID),
                new ActionListenerWrapper(connectActionListener),
                Binder.getCallingUid());
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();

        // Verify that we triggered a second connection.
        verify(mWifiNative, times(2)).connectToNetwork(eq(WIFI_IFACE_NAME), any());
    }

    /**
     * If caller tries to connect to a new network while still provisioning the current one,
     * the connection attempt should succeed.
     */
    @Test
    public void connectWhileObtainingIp() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        startConnectSuccess();

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("L3ProvisioningState", getCurrentState().getName());

        // Connect to a different network
        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID + 1;
        setupAndStartConnectSequence(config);
        validateSuccessfulConnectSequence(config);

        // Disconnection from previous network.
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        // Ensure we don't end the new connection event.
        verify(mWifiMetrics, never()).endConnectionEvent(
                any(), eq(WifiMetrics.ConnectionEvent.FAILURE_NETWORK_DISCONNECTION),
                anyInt(), anyInt(), anyInt());
        verify(mWifiConnectivityManager).prepareForForcedConnection(FRAMEWORK_NETWORK_ID + 1);
    }

    /**
     * If there is a network removal while still connecting to it, the connection
     * should be aborted.
     */
    @Test
    public void networkRemovalWhileObtainingIp() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        startConnectSuccess();

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("L3ProvisioningState", getCurrentState().getName());
        reset(mWifiNative);

        // Simulate the target network removal & the disconnect trigger.
        WifiConfiguration removedNetwork = new WifiConfiguration();
        removedNetwork.networkId = FRAMEWORK_NETWORK_ID;
        mConfigUpdateListenerCaptor.getValue().onNetworkRemoved(removedNetwork);
        mLooper.dispatchAll();

        verify(mWifiNative).removeNetworkCachedData(FRAMEWORK_NETWORK_ID);
        verify(mWifiNative).disconnect(WIFI_IFACE_NAME);

        when(mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID)).thenReturn(null);
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    /**
     * Tests that manual connection to a network (from settings app) logs the correct nominator ID.
     */
    @Test
    public void testManualConnectNominator() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        WifiConfiguration config = new WifiConfiguration();
        config.networkId = TEST_NETWORK_ID;
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(config);

        IActionListener connectActionListener = mock(IActionListener.class);
        mCmi.connectNetwork(
                new NetworkUpdateResult(TEST_NETWORK_ID),
                new ActionListenerWrapper(connectActionListener),
                Process.SYSTEM_UID);
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();

        verify(mWifiMetrics).setNominatorForNetwork(TEST_NETWORK_ID,
                WifiMetricsProto.ConnectionEvent.NOMINATOR_MANUAL);
    }

    private void startConnectSuccess() throws Exception {
        startConnectSuccess(FRAMEWORK_NETWORK_ID);
    }

    private void startConnectSuccess(int networkId) throws Exception {
        IActionListener connectActionListener = mock(IActionListener.class);
        mCmi.connectNetwork(
                new NetworkUpdateResult(networkId),
                new ActionListenerWrapper(connectActionListener),
                Binder.getCallingUid());
        mLooper.dispatchAll();
        verify(connectActionListener).onSuccess();
    }

    @Test
    public void testDhcpFailure() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        startConnectSuccess();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.ASSOCIATED));
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        verify(mWifiBlocklistMonitor).handleBssidConnectionSuccess(sBSSID, sSSID);

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("L3ProvisioningState", getCurrentState().getName());
        injectDhcpFailure();
        mLooper.dispatchAll();

        verify(mWifiNative).disconnect(WIFI_IFACE_NAME);
        // Verify this is not counted as a IP renewal failure
        verify(mWifiMetrics, never()).incrementIpRenewalFailure();
        // Verifies that WifiLastResortWatchdog be notified
        // by DHCP failure
        verify(mWifiLastResortWatchdog, times(2)).noteConnectionFailureAndTriggerIfNeeded(
                sSSID, sBSSID, WifiLastResortWatchdog.FAILURE_CODE_DHCP);
        verify(mWifiBlocklistMonitor, times(2)).handleBssidConnectionFailure(eq(sBSSID),
                eq(sSSID), eq(WifiBlocklistMonitor.REASON_DHCP_FAILURE), anyInt());
        verify(mWifiBlocklistMonitor, times(2)).handleBssidConnectionFailure(eq(sBSSID), eq(sSSID),
                eq(WifiBlocklistMonitor.REASON_DHCP_FAILURE), anyInt());
        verify(mWifiBlocklistMonitor, never()).handleDhcpProvisioningSuccess(sBSSID, sSSID);
        verify(mWifiBlocklistMonitor, never()).handleNetworkValidationSuccess(sBSSID, sSSID);
    }

    /**
     * Verify that a IP renewal failure is logged when IP provisioning fail in the
     * L3ConnectedState.
     */
    @Test
    public void testDhcpRenewalMetrics() throws Exception {
        connect();
        injectDhcpFailure();
        mLooper.dispatchAll();

        verify(mWifiMetrics).incrementIpRenewalFailure();
    }

    /**
     * Verify that the network selection status will be updated with DISABLED_AUTHENTICATION_FAILURE
     * when wrong password authentication failure is detected and the network had been
     * connected previously.
     */
    @Test
    public void testWrongPasswordWithPreviouslyConnected() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        startConnectSuccess();

        WifiConfiguration config = createTestNetwork(false);
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        verify(mWrongPasswordNotifier, never()).onWrongPasswordError(anyString());
        verify(mWifiConfigManager).updateNetworkSelectionStatus(anyInt(),
                eq(WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE));

        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    /**
     * It is observed sometimes the WifiMonitor.NETWORK_DISCONNECTION_EVENT is observed before the
     * actual connection failure messages while making a connection.
     * The test make sure that make sure that the connection event is ended properly in the above
     * case.
     */
    @Test
    public void testDisconnectionEventInL2ConnectingStateEndsConnectionEvent() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        startConnectSuccess();

        WifiConfiguration config = createTestNetwork(false);
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);

        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        verify(mWifiMetrics).endConnectionEvent(
                any(), eq(WifiMetrics.ConnectionEvent.FAILURE_NETWORK_DISCONNECTION),
                anyInt(), anyInt(), anyInt());
        verify(mWifiConnectivityManager).handleConnectionAttemptEnded(
                any(), anyInt(), any(), any());
        assertEquals("DisconnectedState", getCurrentState().getName());
        verify(mPasspointManager).clearTermsAndConditionsUrl();
    }

    /**
     * Verify that the network selection status will be updated with DISABLED_BY_WRONG_PASSWORD
     * when wrong password authentication failure is detected and the network has never been
     * connected.
     */
    @Test
    public void testWrongPasswordWithNeverConnected() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        startConnectSuccess();

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.getNetworkSelectionStatus().setHasEverConnected(false);
        config.carrierId = CARRIER_ID_1;
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        verify(mWrongPasswordNotifier).onWrongPasswordError(eq(sSSID));
        verify(mWifiConfigManager).updateNetworkSelectionStatus(anyInt(),
                eq(WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD));
        verify(mWifiMetrics).incrementNumOfCarrierWifiConnectionAuthFailure();
        assertEquals("DisconnectedState", getCurrentState().getName());
    }

    /**
     * Verify that the function resetCarrierKeysForImsiEncryption() in TelephonyManager
     * is called when a Authentication failure is detected with a vendor specific EAP Error
     * of certification expired while using EAP-SIM
     * In this test case, it is assumed that the network had been connected previously.
     */
    @Test
    public void testEapSimErrorVendorSpecific() throws Exception {
        when(mWifiMetrics.startConnectionEvent(any(), any(), anyString(), anyInt()))
                .thenReturn(80000);
        initializeAndAddNetworkAndVerifySuccess();

        startConnectSuccess();

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);
        MockitoSession mockSession = ExtendedMockito.mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        when(SubscriptionManager.getDefaultDataSubscriptionId()).thenReturn(DATA_SUBID);
        when(SubscriptionManager.isValidSubscriptionId(anyInt())).thenReturn(true);
        when(mWifiScoreCard.detectAbnormalConnectionFailure(anyString()))
                .thenReturn(WifiHealthMonitor.REASON_AUTH_FAILURE);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE,
                WifiNative.EAP_SIM_VENDOR_SPECIFIC_CERT_EXPIRED);
        mLooper.dispatchAll();

        verify(mEapFailureNotifier).onEapFailure(
                WifiNative.EAP_SIM_VENDOR_SPECIFIC_CERT_EXPIRED, config);
        verify(mDataTelephonyManager).resetCarrierKeysForImsiEncryption();
        mockSession.finishMocking();
        verify(mDeviceConfigFacade).isAbnormalConnectionFailureBugreportEnabled();
        verify(mWifiScoreCard).detectAbnormalConnectionFailure(anyString());
        verify(mWifiDiagnostics, times(2)).takeBugReport(anyString(), anyString());
    }

    /**
     * Verify that the function resetCarrierKeysForImsiEncryption() in TelephonyManager
     * is not called when a Authentication failure is detected with a vendor specific EAP Error
     * of certification expired while using other methods than EAP-SIM, EAP-AKA, or EAP-AKA'.
     */
    @Test
    public void testEapTlsErrorVendorSpecific() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        startConnectSuccess();

        WifiConfiguration config = new WifiConfiguration();
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE,
                WifiNative.EAP_SIM_VENDOR_SPECIFIC_CERT_EXPIRED);
        mLooper.dispatchAll();

        verify(mDataTelephonyManager, never()).resetCarrierKeysForImsiEncryption();
    }

    /**
     * Verify that the network selection status will be updated with
     * DISABLED_AUTHENTICATION_NO_SUBSCRIBED when service is not subscribed.
     */
    @Test
    public void testEapSimNoSubscribedError() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        startConnectSuccess();

        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(null);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE,
                WifiNative.EAP_SIM_NOT_SUBSCRIBED);
        mLooper.dispatchAll();

        verify(mWifiConfigManager).updateNetworkSelectionStatus(anyInt(),
                eq(WifiConfiguration.NetworkSelectionStatus
                        .DISABLED_AUTHENTICATION_NO_SUBSCRIPTION));
    }

    @Test
    public void testBadNetworkEvent() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        startConnectSuccess();

        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
        verify(mWifiDiagnostics, never()).takeBugReport(anyString(), anyString());
    }


    @Test
    public void getWhatToString() throws Exception {
        assertEquals("CMD_PRE_DHCP_ACTION", mCmi.getWhatToString(CMD_PRE_DHCP_ACTION));
        assertEquals("CMD_IP_REACHABILITY_LOST", mCmi.getWhatToString(
                ClientModeImpl.CMD_IP_REACHABILITY_LOST));
    }

    @Test
    public void disconnect() throws Exception {
        when(mWifiScoreCard.detectAbnormalDisconnection())
                .thenReturn(WifiHealthMonitor.REASON_SHORT_CONNECTION_NONLOCAL);
        InOrder inOrderWifiLockManager = inOrder(mWifiLockManager);
        connect();
        inOrderWifiLockManager.verify(mWifiLockManager)
                .updateWifiClientConnected(mClientModeManager, true);

        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(mConnectedNetwork.SSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, WifiSsid.createFromAsciiEncoded(mConnectedNetwork.SSID),
                        sBSSID, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        verify(mWifiStateTracker).updateState(eq(WifiStateTracker.DISCONNECTED));
        verify(mWifiNetworkSuggestionsManager).handleDisconnect(any(), any());
        assertEquals("DisconnectedState", getCurrentState().getName());
        inOrderWifiLockManager.verify(mWifiLockManager)
                .updateWifiClientConnected(mClientModeManager, false);
        verify(mWifiScoreCard).detectAbnormalDisconnection();
        verify(mWifiDiagnostics).takeBugReport(anyString(), anyString());
        verify(mWifiNative).disableNetwork(WIFI_IFACE_NAME);
        // Set MAC address thrice - once at bootup, once for new connection, once for disconnect.
        verify(mWifiNative, times(3)).setStaMacAddress(eq(WIFI_IFACE_NAME), any());
        verify(mCountryCode, times(2)).setReadyForChange(true);
    }

    /**
     * Successfully connecting to a network will set WifiConfiguration's value of HasEverConnected
     * to true.
     *
     * Test: Successfully create and connect to a network. Check the config and verify
     * WifiConfiguration.getHasEverConnected() is true.
     */
    @Test
    public void setHasEverConnectedTrueOnConnect() throws Exception {
        connect();
        verify(mWifiConfigManager, atLeastOnce()).updateNetworkAfterConnect(eq(0), eq(false),
                anyInt());
    }

    /**
     * Fail network connection attempt and verify HasEverConnected remains false.
     *
     * Test: Successfully create a network but fail when connecting. Check the config and verify
     * WifiConfiguration.getHasEverConnected() is false.
     */
    @Test
    public void connectionFailureDoesNotSetHasEverConnectedTrue() throws Exception {
        testDhcpFailure();
        verify(mWifiConfigManager, never()).updateNetworkAfterConnect(eq(0), eq(false), anyInt());
    }

    @Test
    public void iconQueryTest() throws Exception {
        // TODO(b/31065385): Passpoint config management.
    }

    @Test
    public void verboseLogRecSizeIsGreaterThanNormalSize() {
        assertTrue(LOG_REC_LIMIT_IN_VERBOSE_MODE > ClientModeImpl.NUM_LOG_RECS_NORMAL);
    }

    /**
     * Verifies that, by default, we allow only the "normal" number of log records.
     */
    @Test
    public void normalLogRecSizeIsUsedByDefault() {
        mCmi.enableVerboseLogging(false);
        assertEquals(ClientModeImpl.NUM_LOG_RECS_NORMAL, mCmi.getLogRecMaxSize());
    }

    /**
     * Verifies that, in verbose mode, we allow a larger number of log records.
     */
    @Test
    public void enablingVerboseLoggingUpdatesLogRecSize() {
        mCmi.enableVerboseLogging(true);
        assertEquals(LOG_REC_LIMIT_IN_VERBOSE_MODE, mCmi.getLogRecMaxSize());
    }

    @Test
    public void disablingVerboseLoggingClearsRecords() {
        mCmi.sendMessage(ClientModeImpl.CMD_DISCONNECT);
        mLooper.dispatchAll();
        assertTrue(mCmi.getLogRecSize() >= 1);

        mCmi.enableVerboseLogging(false);
        assertEquals(0, mCmi.getLogRecSize());
    }

    @Test
    public void disablingVerboseLoggingUpdatesLogRecSize() {
        mCmi.enableVerboseLogging(true);
        mCmi.enableVerboseLogging(false);
        assertEquals(ClientModeImpl.NUM_LOG_RECS_NORMAL, mCmi.getLogRecMaxSize());
    }

    @Test
    public void logRecsIncludeDisconnectCommand() {
        // There's nothing special about the DISCONNECT command. It's just representative of
        // "normal" commands.
        mCmi.sendMessage(ClientModeImpl.CMD_DISCONNECT);
        mLooper.dispatchAll();
        assertEquals(1, mCmi.copyLogRecs()
                .stream()
                .filter(logRec -> logRec.getWhat() == ClientModeImpl.CMD_DISCONNECT)
                .count());
    }

    @Test
    public void logRecsExcludeRssiPollCommandByDefault() {
        mCmi.enableVerboseLogging(false);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL);
        mLooper.dispatchAll();
        assertEquals(0, mCmi.copyLogRecs()
                .stream()
                .filter(logRec -> logRec.getWhat() == ClientModeImpl.CMD_RSSI_POLL)
                .count());
    }

    @Test
    public void logRecsIncludeRssiPollCommandWhenVerboseLoggingIsEnabled() {
        mCmi.enableVerboseLogging(true);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL);
        mLooper.dispatchAll();
        assertEquals(1, mCmi.copyLogRecs()
                .stream()
                .filter(logRec -> logRec.getWhat() == ClientModeImpl.CMD_RSSI_POLL)
                .count());
    }

    /**
     * Verify that syncStartSubscriptionProvisioning will redirect calls with right parameters
     * to {@link PasspointManager} with expected true being returned when in client mode.
     */
    @Test
    public void syncStartSubscriptionProvisioningInClientMode() throws Exception {
        when(mPasspointManager.startSubscriptionProvisioning(anyInt(),
                any(OsuProvider.class), any(IProvisioningCallback.class))).thenReturn(true);
        mLooper.startAutoDispatch();
        assertTrue(mCmi.syncStartSubscriptionProvisioning(
                OTHER_USER_UID, mOsuProvider, mProvisioningCallback));
        verify(mPasspointManager).startSubscriptionProvisioning(OTHER_USER_UID, mOsuProvider,
                mProvisioningCallback);
        mLooper.stopAutoDispatch();
    }

    @Test
    public void testSyncGetCurrentNetwork() throws Exception {
        // syncGetCurrentNetwork() returns null when disconnected
        mLooper.startAutoDispatch();
        assertNull(mCmi.syncGetCurrentNetwork());
        mLooper.stopAutoDispatch();

        connect();

        // syncGetCurrentNetwork() returns non-null Network when connected
        mLooper.startAutoDispatch();
        assertEquals(mNetwork, mCmi.syncGetCurrentNetwork());
        mLooper.stopAutoDispatch();
    }

    /**
     *  Test that we disconnect from a network if it was removed while we are in the
     *  L3ProvisioningState.
     */
    @Test
    public void disconnectFromNetworkWhenRemovedWhileObtainingIpAddr() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        startConnectSuccess();

        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);

        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("L3ProvisioningState", getCurrentState().getName());

        // trigger removal callback to trigger disconnect.
        WifiConfiguration removedConfig = new WifiConfiguration();
        removedConfig.networkId = FRAMEWORK_NETWORK_ID;
        mConfigUpdateListenerCaptor.getValue().onNetworkRemoved(removedConfig);

        reset(mWifiConfigManager);

        when(mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID)).thenReturn(null);

        DhcpResultsParcelable dhcpResults = new DhcpResultsParcelable();
        dhcpResults.baseConfiguration = new StaticIpConfiguration();
        dhcpResults.baseConfiguration.gateway = InetAddresses.parseNumericAddress("1.2.3.4");
        dhcpResults.baseConfiguration.ipAddress =
                new LinkAddress(InetAddresses.parseNumericAddress("192.168.1.100"), 0);
        dhcpResults.baseConfiguration.dnsServers.add(InetAddresses.parseNumericAddress("8.8.8.8"));
        dhcpResults.leaseDuration = 3600;

        injectDhcpSuccess(dhcpResults);
        mLooper.dispatchAll();

        verify(mWifiNative, times(2)).disconnect(WIFI_IFACE_NAME);
    }

    /**
     * Verifies that WifiInfo is updated upon SUPPLICANT_STATE_CHANGE_EVENT.
     */
    @Test
    public void testWifiInfoUpdatedUponSupplicantStateChangedEvent() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        // Set the scan detail cache for roaming target.
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID1)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID1, sFreq1));
        when(mScanDetailCache.getScanResult(sBSSID1)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID1, sFreq1).getScanResult());

        // This simulates the behavior of roaming to network with |sBSSID1|, |sFreq1|.
        // Send a SUPPLICANT_STATE_CHANGE_EVENT, verify WifiInfo is updated.
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID1, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mWifiInfo;
        assertEquals(sBSSID1, wifiInfo.getBSSID());
        assertEquals(sFreq1, wifiInfo.getFrequency());
        assertEquals(SupplicantState.COMPLETED, wifiInfo.getSupplicantState());

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID1, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        wifiInfo = mWifiInfo;
        assertNull(wifiInfo.getBSSID());
        assertEquals(WifiManager.UNKNOWN_SSID, wifiInfo.getSSID());
        assertEquals(WifiConfiguration.INVALID_NETWORK_ID, wifiInfo.getNetworkId());
        assertEquals(SupplicantState.DISCONNECTED, wifiInfo.getSupplicantState());
    }

    /**
     * Verifies that WifiInfo is updated upon CMD_ASSOCIATED_BSSID event.
     */
    @Test
    public void testWifiInfoUpdatedUponAssociatedBSSIDEvent() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        // Set the scan detail cache for roaming target.
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID1)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID1, sFreq1));
        when(mScanDetailCache.getScanResult(sBSSID1)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID1, sFreq1).getScanResult());

        // This simulates the behavior of roaming to network with |sBSSID1|, |sFreq1|.
        // Send a CMD_ASSOCIATED_BSSID, verify WifiInfo is updated.
        mCmi.sendMessage(WifiMonitor.ASSOCIATED_BSSID_EVENT, 0, 0, sBSSID1);
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mWifiInfo;
        assertEquals(sBSSID1, wifiInfo.getBSSID());
        assertEquals(sFreq1, wifiInfo.getFrequency());
        assertEquals(SupplicantState.COMPLETED, wifiInfo.getSupplicantState());
        verify(mContext, times(2)).sendStickyBroadcastAsUser(
                argThat(new NetworkStateChangedIntentMatcher(CONNECTED)), any());
    }

    /**
     * Verifies that WifiInfo is cleared upon exiting and entering WifiInfo, and that it is not
     * updated by SUPPLICAN_STATE_CHANGE_EVENTs in ScanModeState.
     * This protects ClientModeImpl from  getting into a bad state where WifiInfo says wifi is
     * already Connected or Connecting, (when it is in-fact Disconnected), so
     * WifiConnectivityManager does not attempt any new Connections, freezing wifi.
     */
    @Test
    public void testWifiInfoCleanedUpEnteringExitingConnectableState() throws Exception {
        InOrder inOrderMetrics = inOrder(mWifiMetrics);
        Log.i(TAG, mCmi.getCurrentState().getName());
        String initialBSSID = "aa:bb:cc:dd:ee:ff";
        WifiInfo wifiInfo = mWifiInfo;
        wifiInfo.setBSSID(initialBSSID);

        // reset mWifiNative since initializeCmi() was called in setup()
        resetWifiNative();

        // Set CMI to CONNECT_MODE and verify state, and wifi enabled in ConnectivityManager
        initializeCmi();
        inOrderMetrics.verify(mWifiMetrics)
                .setWifiState(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED);
        inOrderMetrics.verify(mWifiMetrics)
                .logStaEvent(WIFI_IFACE_NAME, StaEvent.TYPE_WIFI_ENABLED);
        assertNull(wifiInfo.getBSSID());

        // Send a SUPPLICANT_STATE_CHANGE_EVENT, verify WifiInfo is updated
        connect();
        assertEquals(sBSSID, wifiInfo.getBSSID());
        assertEquals(SupplicantState.COMPLETED, wifiInfo.getSupplicantState());

        // Set CMI to DISABLED_MODE, verify state and wifi disabled in ConnectivityManager, and
        // WifiInfo is reset() and state set to DISCONNECTED
        mCmi.stop();
        mLooper.dispatchAll();

        inOrderMetrics.verify(mWifiMetrics).setWifiState(WifiMetricsProto.WifiLog.WIFI_DISABLED);
        inOrderMetrics.verify(mWifiMetrics)
                .logStaEvent(WIFI_IFACE_NAME, StaEvent.TYPE_WIFI_DISABLED);
        assertNull(wifiInfo.getBSSID());
        assertEquals(SupplicantState.DISCONNECTED, wifiInfo.getSupplicantState());
        verify(mPasspointManager).clearAnqpRequestsAndFlushCache();
    }

    @Test
    public void testWifiInfoCleanedUpEnteringExitingConnectableState2() throws Exception {
        String initialBSSID = "aa:bb:cc:dd:ee:ff";
        InOrder inOrderMetrics = inOrder(mWifiMetrics);

        // Send a SUPPLICANT_STATE_CHANGE_EVENT, verify WifiInfo is not updated
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();
        assertNull(mWifiInfo.getBSSID());
        assertEquals(SupplicantState.DISCONNECTED, mWifiInfo.getSupplicantState());
    }

    @Test
    public void testWifiInfoCleanedUpEnteringExitingConnectableState3() throws Exception {
        String initialBSSID = "aa:bb:cc:dd:ee:ff";
        InOrder inOrderMetrics = inOrder(mWifiMetrics);

        // Set the bssid to something, so we can verify it is cleared (just in case)
        mWifiInfo.setBSSID(initialBSSID);

        initializeCmi();

        inOrderMetrics.verify(mWifiMetrics)
                .setWifiState(WifiMetricsProto.WifiLog.WIFI_DISCONNECTED);
        inOrderMetrics.verify(mWifiMetrics)
                .logStaEvent(WIFI_IFACE_NAME, StaEvent.TYPE_WIFI_ENABLED);
        assertEquals("DisconnectedState", getCurrentState().getName());
        assertEquals(SupplicantState.DISCONNECTED, mWifiInfo.getSupplicantState());
        assertNull(mWifiInfo.getBSSID());
    }

    /**
     * Test that connected SSID and BSSID are exposed to system server.
     * Also tests that {@link ClientModeImpl#syncRequestConnectionInfo()} always
     * returns a copy of WifiInfo.
     */
    @Test
    public void testConnectedIdsAreVisibleFromSystemServer() throws Exception {
        WifiInfo wifiInfo = mWifiInfo;
        // Get into a connected state, with known BSSID and SSID
        connect();
        assertEquals(sBSSID, wifiInfo.getBSSID());
        assertEquals(sWifiSsid, wifiInfo.getWifiSsid());

        mLooper.startAutoDispatch();
        WifiInfo connectionInfo = mCmi.syncRequestConnectionInfo();
        mLooper.stopAutoDispatch();

        assertEquals(wifiInfo.getSSID(), connectionInfo.getSSID());
        assertEquals(wifiInfo.getBSSID(), connectionInfo.getBSSID());
        assertEquals(wifiInfo.getMacAddress(), connectionInfo.getMacAddress());
    }

    /**
     * Test that reconnectCommand() triggers connectivity scan when ClientModeImpl
     * is in DisconnectedMode.
     */
    @Test
    public void testReconnectCommandWhenDisconnected() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|, and then disconnect.
        disconnect();

        mCmi.reconnect(ClientModeImpl.WIFI_WORK_SOURCE);
        mLooper.dispatchAll();
        verify(mWifiConnectivityManager).forceConnectivityScan(ClientModeImpl.WIFI_WORK_SOURCE);
    }

    /**
     * Test that reconnectCommand() doesn't trigger connectivity scan when ClientModeImpl
     * is in ConnectedMode.
     */
    @Test
    public void testReconnectCommandWhenConnected() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        mCmi.reconnect(ClientModeImpl.WIFI_WORK_SOURCE);
        mLooper.dispatchAll();
        verify(mWifiConnectivityManager, never())
                .forceConnectivityScan(ClientModeImpl.WIFI_WORK_SOURCE);
    }

    /**
     * Verifies that ClientModeImpl sets and unsets appropriate 'RecentFailureReason' values
     * on a WifiConfiguration when it fails association, authentication, or successfully connects
     */
    @Test
    public void testExtraFailureReason_ApIsBusy() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        // Trigger a connection to this (CMD_START_CONNECT will actually fail, but it sets up
        // targetNetworkId state)
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        // Simulate an ASSOCIATION_REJECTION_EVENT, due to the AP being busy
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(sSSID, sBSSID,
                        ISupplicantStaIfaceCallback.StatusCode.AP_UNABLE_TO_HANDLE_NEW_STA, false));
        mLooper.dispatchAll();
        verify(mWifiConfigManager).setRecentFailureAssociationStatus(eq(0),
                eq(WifiConfiguration.RECENT_FAILURE_AP_UNABLE_TO_HANDLE_NEW_STA));
        assertEquals("DisconnectedState", getCurrentState().getName());

        // Simulate an AUTHENTICATION_FAILURE_EVENT, which should clear the ExtraFailureReason
        reset(mWifiConfigManager);
        initializeAndAddNetworkAndVerifySuccess();
        // Trigger a connection to this (CMD_START_CONNECT will actually fail, but it sets up
        // targetNetworkId state)
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT, 0, 0, null);
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).clearRecentFailureReason(eq(0));
        verify(mWifiConfigManager, never()).setRecentFailureAssociationStatus(anyInt(), anyInt());

        // Simulate a NETWORK_CONNECTION_EVENT which should clear the ExtraFailureReason
        reset(mWifiConfigManager);
        initializeAndAddNetworkAndVerifySuccess();
        // Trigger a connection to this (CMD_START_CONNECT will actually fail, but it sets up
        // targetNetworkId state)
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, null);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).clearRecentFailureReason(eq(0));
        verify(mWifiConfigManager, never()).setRecentFailureAssociationStatus(anyInt(), anyInt());
    }

    private WifiConfiguration makeLastSelectedWifiConfiguration(int lastSelectedNetworkId,
            long timeSinceLastSelected) {
        long lastSelectedTimestamp = 45666743454L;

        when(mClock.getElapsedSinceBootMillis()).thenReturn(
                lastSelectedTimestamp + timeSinceLastSelected);
        when(mWifiConfigManager.getLastSelectedTimeStamp()).thenReturn(lastSelectedTimestamp);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(lastSelectedNetworkId);

        WifiConfiguration currentConfig = new WifiConfiguration();
        currentConfig.networkId = lastSelectedNetworkId;
        return currentConfig;
    }

    /**
     * Test that the helper method
     * {@link ClientModeImpl#isRecentlySelectedByTheUser(WifiConfiguration)}
     * returns true when we connect to the last selected network before expiration of
     * {@link ClientModeImpl#LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS}.
     */
    @Test
    public void testIsRecentlySelectedByTheUser_SameNetworkNotExpired() {
        WifiConfiguration currentConfig = makeLastSelectedWifiConfiguration(5,
                ClientModeImpl.LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS - 1);
        assertTrue(mCmi.isRecentlySelectedByTheUser(currentConfig));
    }

    /**
     * Test that the helper method
     * {@link ClientModeImpl#isRecentlySelectedByTheUser(WifiConfiguration)}
     * returns false when we connect to the last selected network after expiration of
     * {@link ClientModeImpl#LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS}.
     */
    @Test
    public void testIsRecentlySelectedByTheUser_SameNetworkExpired() {
        WifiConfiguration currentConfig = makeLastSelectedWifiConfiguration(5,
                ClientModeImpl.LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS + 1);
        assertFalse(mCmi.isRecentlySelectedByTheUser(currentConfig));
    }

    /**
     * Test that the helper method
     * {@link ClientModeImpl#isRecentlySelectedByTheUser(WifiConfiguration)}
     * returns false when we connect to a different network to the last selected network.
     */
    @Test
    public void testIsRecentlySelectedByTheUser_DifferentNetwork() {
        WifiConfiguration currentConfig = makeLastSelectedWifiConfiguration(5,
                ClientModeImpl.LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS - 1);
        currentConfig.networkId = 4;
        assertFalse(mCmi.isRecentlySelectedByTheUser(currentConfig));
    }

    private void expectRegisterNetworkAgent(Consumer<NetworkAgentConfig> configChecker,
            Consumer<NetworkCapabilities> networkCapabilitiesChecker) {
        // Expects that the code calls registerNetworkAgent and provides a way for the test to
        // verify the messages sent through the NetworkAgent to ConnectivityService.
        // We cannot just use a mock object here because mWifiNetworkAgent is private to CMI.
        // TODO (b/134538181): consider exposing WifiNetworkAgent and using mocks.
        ArgumentCaptor<INetworkAgent> naCaptor = ArgumentCaptor.forClass(INetworkAgent.class);
        ArgumentCaptor<NetworkAgentConfig> configCaptor =
                ArgumentCaptor.forClass(NetworkAgentConfig.class);
        ArgumentCaptor<NetworkCapabilities> networkCapabilitiesCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        verify(mConnectivityManager).registerNetworkAgent(naCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class),
                networkCapabilitiesCaptor.capture(),
                anyInt(), configCaptor.capture(), anyInt());

        mNetworkAgentBinder = naCaptor.getValue();
        configChecker.accept(configCaptor.getValue());
        networkCapabilitiesChecker.accept(networkCapabilitiesCaptor.getValue());
        replyNetworkAgentRegistered(mNetworkAgentBinder);
    }

    private void replyNetworkAgentRegistered(INetworkAgent agent) {
        new Handler(mLooper.getLooper()).post(() -> {
            try {
                agent.onRegistered(mNetworkAgentRegistry);
            } catch (RemoteException e) {
                throw new AssertionError("Error connecting NetworkAgent", e);
            }
        });
        mLooper.dispatchAll();
    }

    private void expectUnregisterNetworkAgent() throws Exception {
        // We cannot just use a mock object here because mWifiNetworkAgent is private to CMI.
        // TODO (b/134538181): consider exposing WifiNetworkAgent and using mocks.
        final ArgumentCaptor<NetworkInfo> captor = ArgumentCaptor.forClass(NetworkInfo.class);
        mLooper.dispatchAll();
        verify(mNetworkAgentRegistry).sendNetworkInfo(captor.capture());
        assertEquals(NetworkInfo.DetailedState.DISCONNECTED, captor.getValue().getDetailedState());
    }

    private void expectNetworkAgentUpdateCapabilities(
            Consumer<NetworkCapabilities> networkCapabilitiesChecker) throws Exception {
        // We cannot just use a mock object here because mWifiNetworkAgent is private to CMI.
        // TODO (b/134538181): consider exposing WifiNetworkAgent and using mocks.
        ArgumentCaptor<NetworkCapabilities> captor = ArgumentCaptor.forClass(
                NetworkCapabilities.class);
        mLooper.dispatchAll();
        verify(mNetworkAgentRegistry).sendNetworkCapabilities(captor.capture());
        networkCapabilitiesChecker.accept(captor.getValue());
    }

    /**
     * Verify that when a network is explicitly selected, but noInternetAccessExpected is false,
     * the {@link NetworkAgentConfig} contains the right values of explicitlySelected,
     * acceptUnvalidated and acceptPartialConnectivity.
     */
    @Test
    public void testExplicitlySelected_ExplicitInternetExpected() throws Exception {
        // Network is explicitly selected.
        WifiConfiguration config = makeLastSelectedWifiConfiguration(FRAMEWORK_NETWORK_ID,
                ClientModeImpl.LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS - 1);
        mConnectedNetwork.noInternetAccessExpected = false;

        connect();
        expectRegisterNetworkAgent((agentConfig) -> {
            assertTrue(agentConfig.explicitlySelected);
            assertFalse(agentConfig.acceptUnvalidated);
            assertFalse(agentConfig.acceptPartialConnectivity);
        }, (cap) -> { });
    }

    /**
     * Verify that when a network is not explicitly selected, but noInternetAccessExpected is true,
     * the {@link NetworkAgentConfig} contains the right values of explicitlySelected,
     * acceptUnvalidated and acceptPartialConnectivity.
     */
    @Test
    public void testExplicitlySelected_NotExplicitNoInternetExpected() throws Exception {
        // Network is no longer explicitly selected.
        WifiConfiguration config = makeLastSelectedWifiConfiguration(FRAMEWORK_NETWORK_ID,
                ClientModeImpl.LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS + 1);
        mConnectedNetwork.noInternetAccessExpected = true;

        connect();
        expectRegisterNetworkAgent((agentConfig) -> {
            assertFalse(agentConfig.explicitlySelected);
            assertFalse(agentConfig.acceptUnvalidated);
            assertTrue(agentConfig.acceptPartialConnectivity);
        }, (cap) -> { });
    }

    /**
     * Verify that when a network is explicitly selected, and noInternetAccessExpected is true,
     * the {@link NetworkAgentConfig} contains the right values of explicitlySelected,
     * acceptUnvalidated and acceptPartialConnectivity.
     */
    @Test
    public void testExplicitlySelected_ExplicitNoInternetExpected() throws Exception {
        // Network is explicitly selected.
        WifiConfiguration config = makeLastSelectedWifiConfiguration(FRAMEWORK_NETWORK_ID,
                ClientModeImpl.LAST_SELECTED_NETWORK_EXPIRATION_AGE_MILLIS - 1);
        mConnectedNetwork.noInternetAccessExpected = true;

        connect();
        expectRegisterNetworkAgent((agentConfig) -> {
            assertTrue(agentConfig.explicitlySelected);
            assertTrue(agentConfig.acceptUnvalidated);
            assertTrue(agentConfig.acceptPartialConnectivity);
        }, (cap) -> { });
    }

    /**
     * Verify that Rssi Monitoring is started and the callback registered after connecting.
     */
    @Test
    public void verifyRssiMonitoringCallbackIsRegistered() throws Exception {
        // Simulate the first connection.
        connect();
        ArgumentCaptor<INetworkAgent> agentCaptor = ArgumentCaptor.forClass(INetworkAgent.class);
        verify(mConnectivityManager).registerNetworkAgent(agentCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkAgentConfig.class), anyInt());

        ArrayList<Integer> thresholdsArray = new ArrayList<>();
        thresholdsArray.add(RSSI_THRESHOLD_MAX);
        thresholdsArray.add(RSSI_THRESHOLD_MIN);
        agentCaptor.getValue().onSignalStrengthThresholdsUpdated(
                thresholdsArray.stream().mapToInt(Integer::intValue).toArray());
        mLooper.dispatchAll();

        ArgumentCaptor<WifiNative.WifiRssiEventHandler> rssiEventHandlerCaptor =
                ArgumentCaptor.forClass(WifiNative.WifiRssiEventHandler.class);
        verify(mWifiNative).startRssiMonitoring(anyString(), anyByte(), anyByte(),
                rssiEventHandlerCaptor.capture());

        // breach below min
        rssiEventHandlerCaptor.getValue().onRssiThresholdBreached(RSSI_THRESHOLD_BREACH_MIN);
        mLooper.dispatchAll();
        WifiInfo wifiInfo = mWifiInfo;
        assertEquals(RSSI_THRESHOLD_BREACH_MIN, wifiInfo.getRssi());

        // breach above max
        rssiEventHandlerCaptor.getValue().onRssiThresholdBreached(RSSI_THRESHOLD_BREACH_MAX);
        mLooper.dispatchAll();
        assertEquals(RSSI_THRESHOLD_BREACH_MAX, wifiInfo.getRssi());
    }

    /**
     * Verify that RSSI and link layer stats polling works in connected mode
     */
    @Test
    public void verifyConnectedModeRssiPolling() throws Exception {
        final long startMillis = 1_500_000_000_100L;
        WifiLinkLayerStats llStats = new WifiLinkLayerStats();
        llStats.txmpdu_be = 1000;
        llStats.rxmpdu_bk = 2000;
        WifiNl80211Manager.SignalPollResult signalPollResult =
                new WifiNl80211Manager.SignalPollResult(-42, 65, 54, sFreq);
        when(mWifiNative.getWifiLinkLayerStats(any())).thenReturn(llStats);
        when(mWifiNative.signalPoll(any())).thenReturn(signalPollResult);
        when(mClock.getWallClockMillis()).thenReturn(startMillis + 0);
        mCmi.enableRssiPolling(true);
        connect();
        mLooper.dispatchAll();
        when(mClock.getWallClockMillis()).thenReturn(startMillis + 3333);
        mLooper.dispatchAll();
        WifiInfo wifiInfo = mWifiInfo;
        assertEquals(llStats.txmpdu_be, wifiInfo.txSuccess);
        assertEquals(llStats.rxmpdu_bk, wifiInfo.rxSuccess);
        assertEquals(signalPollResult.currentRssiDbm, wifiInfo.getRssi());
        assertEquals(signalPollResult.txBitrateMbps, wifiInfo.getLinkSpeed());
        assertEquals(signalPollResult.txBitrateMbps, wifiInfo.getTxLinkSpeedMbps());
        assertEquals(signalPollResult.rxBitrateMbps, wifiInfo.getRxLinkSpeedMbps());
        assertEquals(sFreq, wifiInfo.getFrequency());
        verify(mWifiDataStall, atLeastOnce()).getTxThroughputKbps();
        verify(mWifiDataStall, atLeastOnce()).getRxThroughputKbps();
        verify(mWifiScoreCard).noteSignalPoll(any());
    }

    /**
     * Verify RSSI polling with verbose logging
     */
    @Test
    public void verifyConnectedModeRssiPollingWithVerboseLogging() throws Exception {
        mCmi.enableVerboseLogging(true);
        verifyConnectedModeRssiPolling();
    }

    /**
     * Verify that calls to start and stop filtering multicast packets are passed on to the IpClient
     * instance.
     */
    @Test
    public void verifyMcastLockManagerFilterControllerCallsUpdateIpClient() throws Exception {
        reset(mIpClient);
        WifiMulticastLockManager.FilterController filterController =
                mCmi.getMcastLockManagerFilterController();
        filterController.startFilteringMulticastPackets();
        verify(mIpClient).setMulticastFilter(eq(true));
        filterController.stopFilteringMulticastPackets();
        verify(mIpClient).setMulticastFilter(eq(false));
    }

    /**
     * Verifies that when
     * 1. Global feature support flag is set to false
     * 2. connected MAC randomization is on and
     * 3. macRandomizationSetting of the WifiConfiguration is RANDOMIZATION_AUTO and
     * 4. randomized MAC for the network to connect to is different from the current MAC.
     *
     * The factory MAC address is used for the connection, and no attempt is made to change it.
     */
    @Test
    public void testConnectedMacRandomizationNotSupported() throws Exception {
        // reset mWifiNative since initializeCmi() was called in setup()
        resetWifiNative();

        when(mWifiGlobals.isConnectedMacRandomizationEnabled()).thenReturn(false);
        initializeCmi();
        initializeAndAddNetworkAndVerifySuccess();

        connect();
        assertEquals(TEST_GLOBAL_MAC_ADDRESS.toString(), mWifiInfo.getMacAddress());
        verify(mWifiNative, never()).setStaMacAddress(any(), any());
        verify(mWifiNative, never()).getStaFactoryMacAddress(any());
    }

    /**
     * Verifies that when
     * 1. connected MAC randomization is on and
     * 2. macRandomizationSetting of the WifiConfiguration is RANDOMIZATION_AUTO and
     * 3. randomized MAC for the network to connect to is different from the current MAC.
     *
     * Then the current MAC gets set to the randomized MAC when CMD_START_CONNECT executes.
     */
    @Test
    public void testConnectedMacRandomizationRandomizationPersistentDifferentMac()
            throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        connect();
        verify(mWifiNative).setStaMacAddress(WIFI_IFACE_NAME, TEST_LOCAL_MAC_ADDRESS);
        verify(mWifiMetrics).logStaEvent(
                eq(WIFI_IFACE_NAME), eq(StaEvent.TYPE_MAC_CHANGE), any(WifiConfiguration.class));
        assertEquals(TEST_LOCAL_MAC_ADDRESS.toString(), mWifiInfo.getMacAddress());
    }

    /**
     * Verifies that when
     * 1. connected MAC randomization is on and
     * 2. macRandomizationSetting of the WifiConfiguration is RANDOMIZATION_AUTO and
     * 3. randomized MAC for the network to connect to is same as the current MAC.
     *
     * Then MAC change should not occur when CMD_START_CONNECT executes.
     */
    @Test
    public void testConnectedMacRandomizationRandomizationPersistentSameMac() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_LOCAL_MAC_ADDRESS.toString());

        connect();
        verify(mWifiNative, never()).setStaMacAddress(WIFI_IFACE_NAME, TEST_LOCAL_MAC_ADDRESS);
        verify(mWifiMetrics, never()).logStaEvent(
                any(), eq(StaEvent.TYPE_MAC_CHANGE), any(WifiConfiguration.class));
        assertEquals(TEST_LOCAL_MAC_ADDRESS.toString(), mWifiInfo.getMacAddress());
    }

    /**
     * Verifies that when
     * 1. connected MAC randomization is on and
     * 2. macRandomizationSetting of the WifiConfiguration is RANDOMIZATION_NONE and
     * 3. current MAC address is not the factory MAC.
     *
     * Then the current MAC gets set to the factory MAC when CMD_START_CONNECT executes.
     * @throws Exception
     */
    @Test
    public void testConnectedMacRandomizationRandomizationNoneDifferentMac() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_LOCAL_MAC_ADDRESS.toString());

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_NONE;
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(0)).thenReturn(config);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(mWifiNative).setStaMacAddress(WIFI_IFACE_NAME, TEST_GLOBAL_MAC_ADDRESS);
        verify(mWifiMetrics).logStaEvent(
                eq(WIFI_IFACE_NAME), eq(StaEvent.TYPE_MAC_CHANGE), any(WifiConfiguration.class));
        assertEquals(TEST_GLOBAL_MAC_ADDRESS.toString(), mWifiInfo.getMacAddress());
    }

    /**
     * Verifies that when
     * 1. connected MAC randomization is on and
     * 2. macRandomizationSetting of the WifiConfiguration is RANDOMIZATION_NONE and
     *
     * Then the factory MAC should be used to connect to the network.
     * @throws Exception
     */
    @Test
    public void testConnectedMacRandomizationRandomizationNoneSameMac() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_NONE;
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(0)).thenReturn(config);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        assertEquals(TEST_GLOBAL_MAC_ADDRESS.toString(), mWifiInfo.getMacAddress());
    }

    /**
     * Verifies that WifiInfo returns DEFAULT_MAC_ADDRESS as mac address when Connected MAC
     * Randomization is on and the device is not connected to a wifi network.
     */
    @Test
    public void testWifiInfoReturnDefaultMacWhenDisconnectedWithRandomization() throws Exception {
        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_LOCAL_MAC_ADDRESS.toString());

        connect();
        assertEquals(TEST_LOCAL_MAC_ADDRESS.toString(), mWifiInfo.getMacAddress());

        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(mConnectedNetwork.SSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, WifiSsid.createFromAsciiEncoded(mConnectedNetwork.SSID),
                        sBSSID, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
        assertEquals(WifiInfo.DEFAULT_MAC_ADDRESS, mWifiInfo.getMacAddress());
        assertFalse(mWifiInfo.hasRealMacAddress());
    }

    /**
     * Verifies that we don't set MAC address when config returns an invalid MAC address.
     */
    @Test
    public void testDoNotSetMacWhenInvalid() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        config.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_AUTO;
        config.setRandomizedMacAddress(MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS));
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(0)).thenReturn(config);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        // setStaMacAddress is invoked once when ClientModeImpl starts to prevent leak of factory
        // MAC.
        verify(mWifiNative).setStaMacAddress(eq(WIFI_IFACE_NAME), any(MacAddress.class));
    }

    /**
     * Verify that we don't crash when WifiNative returns null as the current MAC address.
     * @throws Exception
     */
    @Test
    public void testMacRandomizationWifiNativeReturningNull() throws Exception {
        when(mWifiNative.getMacAddress(anyString())).thenReturn(null);
        initializeAndAddNetworkAndVerifySuccess();

        connect();
        verify(mWifiNative).setStaMacAddress(WIFI_IFACE_NAME, TEST_LOCAL_MAC_ADDRESS);
    }

    /**
     * Verifies that a notification is posted when a connection failure happens on a network
     * in the hotlist. Then verify that tapping on the notification launches an dialog, which
     * could be used to set the randomization setting for a network to "Trusted".
     */
    @Test
    public void testConnectionFailureSendRandomizationSettingsNotification() throws Exception {
        when(mWifiConfigManager.isInFlakyRandomizationSsidHotlist(anyInt())).thenReturn(true);
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, FRAMEWORK_NETWORK_ID, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_TIMEOUT);
        mLooper.dispatchAll();

        WifiConfiguration config = mCmi.getConnectedWifiConfiguration();
        verify(mConnectionFailureNotifier)
                .showFailedToConnectDueToNoRandomizedMacSupportNotification(FRAMEWORK_NETWORK_ID);
    }

    /**
     * Verifies that a notification is not posted when a wrong password failure happens on a
     * network in the hotlist.
     */
    @Test
    public void testNotCallingIsInFlakyRandomizationSsidHotlistOnWrongPassword() throws Exception {
        when(mWifiConfigManager.isInFlakyRandomizationSsidHotlist(anyInt())).thenReturn(true);
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, FRAMEWORK_NETWORK_ID, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
        mLooper.dispatchAll();

        verify(mConnectionFailureNotifier, never())
                .showFailedToConnectDueToNoRandomizedMacSupportNotification(anyInt());
    }

    /**
     * Verifies that CMD_START_CONNECT make WifiDiagnostics report
     * CONNECTION_EVENT_STARTED
     * @throws Exception
     */
    @Test
    public void testReportConnectionEventIsCalledAfterCmdStartConnect() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_STARTED));
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_STARTED));
    }

    /**
     * Verifies that CMD_DIAG_CONNECT_TIMEOUT is processed after the timeout threshold if we
     * start a connection but do not finish it.
     * @throws Exception
     */
    @Test
    public void testCmdDiagsConnectTimeoutIsGeneratedAfterCmdStartConnect() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        mLooper.moveTimeForward(ClientModeImpl.DIAGS_CONNECT_TIMEOUT_MILLIS);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_TIMEOUT));
    }

    /**
     * Verifies that CMD_DIAG_CONNECT_TIMEOUT does not get processed before the timeout threshold.
     * @throws Exception
     */
    @Test
    public void testCmdDiagsConnectTimeoutIsNotProcessedBeforeTimerExpires() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        mLooper.moveTimeForward(ClientModeImpl.DIAGS_CONNECT_TIMEOUT_MILLIS - 1000);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_TIMEOUT));
    }

    private void verifyConnectionEventTimeoutDoesNotOccur() {
        mLooper.moveTimeForward(ClientModeImpl.DIAGS_CONNECT_TIMEOUT_MILLIS);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_TIMEOUT));
    }

    /**
     * Verifies that association failures make WifiDiagnostics report CONNECTION_EVENT_FAILED
     * and then cancel any pending timeouts.
     * Also, send connection status to {@link WifiNetworkFactory} & {@link WifiConnectivityManager}.
     * @throws Exception
     */
    @Test
    public void testReportConnectionEventIsCalledAfterAssociationFailure() throws Exception {
        mConnectedNetwork.getNetworkSelectionStatus()
                .setCandidate(getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(sSSID, sBSSID,
                        ISupplicantStaIfaceCallback.StatusCode.AP_UNABLE_TO_HANDLE_NEW_STA, false));
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        verify(mWifiConnectivityManager).handleConnectionAttemptEnded(
                mClientModeManager,
                WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION, sBSSID, sSSID);
        verify(mWifiNetworkFactory).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION),
                any(WifiConfiguration.class));
        verify(mWifiNetworkSuggestionsManager).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION),
                any(WifiConfiguration.class), eq(null));
        verify(mWifiMetrics, never())
                .incrementNumBssidDifferentSelectionBetweenFrameworkAndFirmware();
        verifyConnectionEventTimeoutDoesNotOccur();
    }

    /**
     * Verifies that authentication failures make WifiDiagnostics report
     * CONNECTION_EVENT_FAILED and then cancel any pending timeouts.
     * Also, send connection status to {@link WifiNetworkFactory} & {@link WifiConnectivityManager}.
     * @throws Exception
     */
    @Test
    public void testReportConnectionEventIsCalledAfterAuthenticationFailure() throws Exception {
        mConnectedNetwork.getNetworkSelectionStatus()
                .setCandidate(getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
        verify(mWifiDiagnostics, never()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        verify(mWifiConnectivityManager).handleConnectionAttemptEnded(
                mClientModeManager,
                WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE, sBSSID, sSSID);
        verify(mWifiNetworkFactory).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE),
                any(WifiConfiguration.class));
        verify(mWifiNetworkSuggestionsManager).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE),
                any(WifiConfiguration.class), eq(null));
        verify(mWifiMetrics, never())
                .incrementNumBssidDifferentSelectionBetweenFrameworkAndFirmware();
        verifyConnectionEventTimeoutDoesNotOccur();
    }

    /**
     * Verify that if a NETWORK_DISCONNECTION_EVENT is received in L3ConnectedState, then an
     * abnormal disconnect is reported to WifiBlocklistMonitor.
     */
    @Test
    public void testAbnormalDisconnectNotifiesWifiBlocklistMonitor() throws Exception {
        // trigger RSSI poll to update WifiInfo
        mCmi.enableRssiPolling(true);
        WifiLinkLayerStats llStats = new WifiLinkLayerStats();
        llStats.txmpdu_be = 1000;
        llStats.rxmpdu_bk = 2000;
        WifiNl80211Manager.SignalPollResult signalPollResult =
                new WifiNl80211Manager.SignalPollResult(TEST_RSSI, 65, 54, sFreq);
        when(mWifiNative.getWifiLinkLayerStats(any())).thenReturn(llStats);
        when(mWifiNative.signalPoll(any())).thenReturn(signalPollResult);

        connect();
        mLooper.dispatchAll();
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(eq(sBSSID), eq(sSSID),
                eq(WifiBlocklistMonitor.REASON_ABNORMAL_DISCONNECT), anyInt());
    }

    /**
     * Verify that ClientModeImpl notifies WifiBlocklistMonitor correctly when the RSSI is
     * too low.
     */
    @Test
    public void testNotifiesWifiBlocklistMonitorLowRssi() throws Exception {
        int testLowRssi = -80;
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, FRAMEWORK_NETWORK_ID, 0,
                ClientModeImpl.SUPPLICANT_BSSID_ANY);
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(sSSID, sBSSID, 0, true));
        when(mWifiConfigManager.findScanRssi(eq(FRAMEWORK_NETWORK_ID), anyInt()))
                .thenReturn(testLowRssi);
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(testLowRssi, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(testLowRssi, sBSSID, sFreq).getScanResult());
        mLooper.dispatchAll();

        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(sBSSID, sSSID,
                WifiBlocklistMonitor.REASON_ASSOCIATION_TIMEOUT, testLowRssi);
    }

    /**
     * Verify that the recent failure association status is updated properly when
     * ASSOC_REJECTED_TEMPORARILY occurs.
     */
    @Test
    public void testAssocRejectedTemporarilyUpdatesRecentAssociationFailureStatus()
            throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(sSSID, sBSSID,
                        ISupplicantStaIfaceCallback.StatusCode.ASSOC_REJECTED_TEMPORARILY,
                        false));
        mLooper.dispatchAll();
        verify(mWifiConfigManager).setRecentFailureAssociationStatus(anyInt(),
                eq(WifiConfiguration.RECENT_FAILURE_REFUSED_TEMPORARILY));
    }

    /**
     * Verify that WifiScoreCard and WifiBlocklistMonitor are notified properly when
     * disconnection occurs in middle of connection states.
     */
    @Test
    public void testDisconnectConnecting()
            throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT,
                new DisconnectEventInfo(sSSID, sBSSID,
                        ISupplicantStaIfaceCallback.ReasonCode.FOURWAY_HANDSHAKE_TIMEOUT,
                        false));
        mLooper.dispatchAll();
        verify(mWifiScoreCard).noteConnectionFailure(any(), anyInt(), anyString(), anyInt());
        verify(mWifiScoreCard).resetConnectionState();
        // Verify that the WifiBlocklistMonitor is notified of a non-locally generated disconnect
        // that occurred mid connection attempt.
        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(anyString(), anyString(),
                eq(WifiBlocklistMonitor.REASON_NONLOCAL_DISCONNECT_CONNECTING), anyInt());
    }

    /**
     * Verify that the recent failure association status is updated properly when
     * DENIED_POOR_CHANNEL_CONDITIONS occurs.
     */
    @Test
    public void testAssocRejectedPoorChannelConditionsUpdatesRecentAssociationFailureStatus()
            throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(sSSID, sBSSID,
                        ISupplicantStaIfaceCallback.StatusCode.DENIED_POOR_CHANNEL_CONDITIONS,
                        false));
        mLooper.dispatchAll();
        verify(mWifiConfigManager).setRecentFailureAssociationStatus(anyInt(),
                eq(WifiConfiguration.RECENT_FAILURE_POOR_CHANNEL_CONDITIONS));
    }

    /**
     * Verify that the recent failure association status is updated properly when a disconnection
     * with reason code DISASSOC_AP_BUSY occurs.
     */
    @Test
    public void testNetworkDisconnectionApBusyUpdatesRecentAssociationFailureStatus()
            throws Exception {
        connect();
        // Disconnection with reason = DISASSOC_AP_BUSY
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 5, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).setRecentFailureAssociationStatus(anyInt(),
                eq(WifiConfiguration.RECENT_FAILURE_DISCONNECTION_AP_BUSY));
    }

    /**
     * Verify that the recent failure association status is updated properly when a disconnection
     * with reason code DISASSOC_AP_BUSY occurs.
     */
    @Test
    public void testMidConnectionDisconnectionApBusyUpdatesRecentAssociationFailureStatus()
            throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        startConnectSuccess();
        assertEquals("L2ConnectingState", getCurrentState().getName());

        // Disconnection with reason = DISASSOC_AP_BUSY
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 5, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();
        verify(mWifiConfigManager).setRecentFailureAssociationStatus(anyInt(),
                eq(WifiConfiguration.RECENT_FAILURE_DISCONNECTION_AP_BUSY));
    }

    /**
     * Verifies that the WifiBlocklistMonitor is notified, but the WifiLastResortWatchdog is
     * not notified of association rejections of type REASON_CODE_AP_UNABLE_TO_HANDLE_NEW_STA.
     * @throws Exception
     */
    @Test
    public void testAssociationRejectionWithReasonApUnableToHandleNewStaUpdatesWatchdog()
            throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(sSSID, sBSSID,
                        ISupplicantStaIfaceCallback.StatusCode.AP_UNABLE_TO_HANDLE_NEW_STA, false));
        mLooper.dispatchAll();
        verify(mWifiLastResortWatchdog, never()).noteConnectionFailureAndTriggerIfNeeded(
                anyString(), anyString(), anyInt());
        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(eq(sBSSID), eq(sSSID),
                eq(WifiBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA), anyInt());
    }

    /**
     * Verifies that the WifiBlocklistMonitor is notified, but the WifiLastResortWatchdog is
     * not notified of association rejections of type DENIED_INSUFFICIENT_BANDWIDTH.
     * @throws Exception
     */
    @Test
    public void testAssociationRejectionWithReasonDeniedInsufficientBandwidth()
            throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(sSSID, sBSSID, ISupplicantStaIfaceCallback
                        .StatusCode.DENIED_INSUFFICIENT_BANDWIDTH, false));
        mLooper.dispatchAll();
        verify(mWifiLastResortWatchdog, never()).noteConnectionFailureAndTriggerIfNeeded(
                anyString(), anyString(), anyInt());
        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(eq(sBSSID), eq(sSSID),
                eq(WifiBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA), anyInt());
    }

    /**
     * Verifies that WifiLastResortWatchdog and WifiBlocklistMonitor is notified of
     * general association rejection failures.
     * @throws Exception
     */
    @Test
    public void testAssociationRejectionUpdatesWatchdog() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        WifiConfiguration config = mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID);
        config.carrierId = CARRIER_ID_1;
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(sSSID, sBSSID, 0, false));
        mLooper.dispatchAll();
        verify(mWifiLastResortWatchdog).noteConnectionFailureAndTriggerIfNeeded(
                anyString(), anyString(), anyInt());
        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(eq(sBSSID), eq(sSSID),
                eq(WifiBlocklistMonitor.REASON_ASSOCIATION_REJECTION), anyInt());
        verify(mWifiMetrics).incrementNumOfCarrierWifiConnectionNonAuthFailure();
    }

    /**
     * Verifies that WifiLastResortWatchdog is not notified of authentication failures of type
     * ERROR_AUTH_FAILURE_WRONG_PSWD.
     * @throws Exception
     */
    @Test
    public void testFailureWrongPassIsIgnoredByWatchdog() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
        mLooper.dispatchAll();
        verify(mWifiLastResortWatchdog, never()).noteConnectionFailureAndTriggerIfNeeded(
                anyString(), anyString(), anyInt());
        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(eq(sBSSID), eq(sSSID),
                eq(WifiBlocklistMonitor.REASON_WRONG_PASSWORD), anyInt());
    }

    /**
     * Verifies that WifiLastResortWatchdog is not notified of authentication failures of type
     * ERROR_AUTH_FAILURE_EAP_FAILURE.
     * @throws Exception
     */
    @Test
    public void testEapFailureIsIgnoredByWatchdog() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE);
        mLooper.dispatchAll();
        verify(mWifiLastResortWatchdog, never()).noteConnectionFailureAndTriggerIfNeeded(
                anyString(), anyString(), anyInt());
        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(eq(sBSSID), eq(sSSID),
                eq(WifiBlocklistMonitor.REASON_EAP_FAILURE), anyInt());
    }

    /**
     * Verifies that WifiLastResortWatchdog is notified of other types of authentication failures.
     * @throws Exception
     */
    @Test
    public void testAuthenticationFailureUpdatesWatchdog() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_TIMEOUT);
        mLooper.dispatchAll();
        verify(mWifiLastResortWatchdog).noteConnectionFailureAndTriggerIfNeeded(
                anyString(), anyString(), anyInt());
        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(eq(sBSSID), eq(sSSID),
                eq(WifiBlocklistMonitor.REASON_AUTHENTICATION_FAILURE), anyInt());
    }

    /**
     * Verify that WifiBlocklistMonitor is notified of the SSID pre-connection so that it could
     * send down to firmware the list of blocked BSSIDs.
     */
    @Test
    public void testBssidBlocklistSentToFirmwareAfterCmdStartConnect() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        verify(mWifiBlocklistMonitor, never()).updateFirmwareRoamingConfiguration(Set.of(sSSID));
        mLooper.dispatchAll();
        verify(mWifiBlocklistMonitor).updateFirmwareRoamingConfiguration(Set.of(sSSID));
        // But don't expect to see connection success yet
        verify(mWifiScoreCard, never()).noteIpConfiguration(any());
        // And certainly not validation success
        verify(mWifiScoreCard, never()).noteValidationSuccess(any());
    }

    /**
     * Verifies that dhcp failures make WifiDiagnostics report CONNECTION_EVENT_FAILED and then
     * cancel any pending timeouts.
     * Also, send connection status to {@link WifiNetworkFactory} & {@link WifiConnectivityManager}.
     * @throws Exception
     */
    @Test
    public void testReportConnectionEventIsCalledAfterDhcpFailure() throws Exception {
        mConnectedNetwork.getNetworkSelectionStatus()
                .setCandidate(getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());
        testDhcpFailure();
        verify(mWifiDiagnostics, atLeastOnce()).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_FAILED));
        verify(mWifiConnectivityManager, atLeastOnce()).handleConnectionAttemptEnded(
                mClientModeManager,
                WifiMetrics.ConnectionEvent.FAILURE_DHCP, sBSSID, sSSID);
        verify(mWifiNetworkFactory, atLeastOnce()).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_DHCP), any(WifiConfiguration.class));
        verify(mWifiNetworkSuggestionsManager, atLeastOnce()).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_DHCP), any(WifiConfiguration.class),
                any(String.class));
        verify(mWifiMetrics, never())
                .incrementNumBssidDifferentSelectionBetweenFrameworkAndFirmware();
        verifyConnectionEventTimeoutDoesNotOccur();
    }

    /**
     * Verifies that a successful validation make WifiDiagnostics report CONNECTION_EVENT_SUCCEEDED
     * and then cancel any pending timeouts.
     * Also, send connection status to {@link WifiNetworkFactory} & {@link WifiConnectivityManager}.
     */
    @Test
    public void testReportConnectionEventIsCalledAfterSuccessfulConnection() throws Exception {
        mConnectedNetwork.getNetworkSelectionStatus()
                .setCandidate(getGoogleGuestScanDetail(TEST_RSSI, sBSSID1, sFreq).getScanResult());
        connect();
        ArgumentCaptor<INetworkAgent> agentCaptor = ArgumentCaptor.forClass(INetworkAgent.class);
        verify(mConnectivityManager).registerNetworkAgent(agentCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkAgentConfig.class), anyInt());
        agentCaptor.getValue()
                .onValidationStatusChanged(NetworkAgent.VALID_NETWORK, null /* captivePortalUrl */);
        mLooper.dispatchAll();

        verify(mWifiDiagnostics).reportConnectionEvent(
                eq(WifiDiagnostics.CONNECTION_EVENT_SUCCEEDED));
        verify(mWifiConnectivityManager).handleConnectionAttemptEnded(
                mClientModeManager,
                WifiMetrics.ConnectionEvent.FAILURE_NONE, sBSSID, sSSID);
        verify(mWifiNetworkFactory).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_NONE), any(WifiConfiguration.class));
        verify(mWifiNetworkSuggestionsManager).handleConnectionAttemptEnded(
                eq(WifiMetrics.ConnectionEvent.FAILURE_NONE), any(WifiConfiguration.class),
                any(String.class));
        verify(mCmiMonitor).onL3Validated(mClientModeManager);
        // BSSID different, record this connection.
        verify(mWifiMetrics).incrementNumBssidDifferentSelectionBetweenFrameworkAndFirmware();
        verifyConnectionEventTimeoutDoesNotOccur();
    }

    /**
     * Verify that score card is notified of a connection attempt
     */
    @Test
    public void testScoreCardNoteConnectionAttemptAfterCmdStartConnect() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        verify(mWifiScoreCard, never()).noteConnectionAttempt(any(), anyInt(), anyString());
        mLooper.dispatchAll();
        verify(mWifiScoreCard).noteConnectionAttempt(any(), anyInt(), anyString());
        verify(mWifiConfigManager).findScanRssi(anyInt(), anyInt());
        // But don't expect to see connection success yet
        verify(mWifiScoreCard, never()).noteIpConfiguration(any());
        // And certainly not validation success
        verify(mWifiScoreCard, never()).noteValidationSuccess(any());

    }

    /**
     * Verify that score card is notified of a successful connection
     */
    @Test
    public void testScoreCardNoteConnectionComplete() throws Exception {
        Pair<String, String> l2KeyAndCluster = Pair.create("Wad", "Gab");
        when(mWifiScoreCard.getL2KeyAndGroupHint(any())).thenReturn(l2KeyAndCluster);
        connect();
        mLooper.dispatchAll();
        verify(mWifiScoreCard).noteIpConfiguration(any());
        ArgumentCaptor<Layer2InformationParcelable> captor =
                ArgumentCaptor.forClass(Layer2InformationParcelable.class);
        verify(mIpClient, atLeastOnce()).updateLayer2Information(captor.capture());
        final Layer2InformationParcelable info = captor.getValue();
        assertEquals(info.l2Key, "Wad");
        assertEquals(info.cluster, "Gab");
    }

    /**
     * Verify that score card/health monitor are notified when wifi is disabled while disconnected
     */
    @Test
    public void testScoreCardNoteWifiDisabledWhileDisconnected() throws Exception {
        // connecting and disconnecting shouldn't note wifi disabled
        disconnect();
        mLooper.dispatchAll();

        verify(mWifiScoreCard, times(1)).resetConnectionState();
        verify(mWifiScoreCard, never()).noteWifiDisabled(any());
        verify(mWifiHealthMonitor, never()).setWifiEnabled(false);

        // disabling while disconnected should note wifi disabled
        mCmi.stop();
        mLooper.dispatchAll();
        verify(mWifiScoreCard, times(2)).resetConnectionState();
        verify(mWifiHealthMonitor).setWifiEnabled(false);
    }

    /**
     * Verify that score card/health monitor are notified when wifi is disabled while connected
     */
    @Test
    public void testScoreCardNoteWifiDisabledWhileConnected() throws Exception {
        // Get into connected state
        connect();
        mLooper.dispatchAll();
        verify(mWifiScoreCard, never()).noteWifiDisabled(any());
        verify(mWifiHealthMonitor, never()).setWifiEnabled(false);

        // disabling while connected should note wifi disabled
        mCmi.stop();
        mLooper.dispatchAll();

        verify(mWifiScoreCard).noteWifiDisabled(any());
        verify(mWifiScoreCard).resetConnectionState();
        verify(mWifiHealthMonitor).setWifiEnabled(false);
    }

    /**
     * Verify that IPClient instance is shutdown when wifi is disabled.
     */
    @Test
    public void verifyIpClientShutdownWhenDisabled() throws Exception {
        mCmi.stop();
        mLooper.dispatchAll();
        verify(mIpClient).shutdown();
        verify(mWifiConfigManager).removeAllEphemeralOrPasspointConfiguredNetworks();
        verify(mWifiConfigManager).clearUserTemporarilyDisabledList();
    }

    /**
     * Verify that WifiInfo's MAC address is updated when the state machine receives
     * NETWORK_CONNECTION_EVENT while in L3ConnectedState.
     */
    @Test
    public void verifyWifiInfoMacUpdatedWithNetworkConnectionWhileConnected() throws Exception {
        connect();
        assertEquals("L3ConnectedState", getCurrentState().getName());
        assertEquals(TEST_LOCAL_MAC_ADDRESS.toString(), mWifiInfo.getMacAddress());

        // Verify receiving a NETWORK_CONNECTION_EVENT changes the MAC in WifiInfo
        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_GLOBAL_MAC_ADDRESS.toString());
        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        assertEquals(TEST_GLOBAL_MAC_ADDRESS.toString(), mWifiInfo.getMacAddress());
    }

    /**
     * Verify that WifiInfo's MAC address is updated when the state machine receives
     * NETWORK_CONNECTION_EVENT while in DisconnectedState.
     */
    @Test
    public void verifyWifiInfoMacUpdatedWithNetworkConnectionWhileDisconnected() throws Exception {
        disconnect();
        assertEquals("DisconnectedState", getCurrentState().getName());
        // Since MAC randomization is enabled, wifiInfo's MAC should be set to default MAC
        // when disconnect happens.
        assertEquals(WifiInfo.DEFAULT_MAC_ADDRESS, mWifiInfo.getMacAddress());

        setupAndStartConnectSequence(mConnectedNetwork);
        when(mWifiNative.getMacAddress(WIFI_IFACE_NAME))
                .thenReturn(TEST_LOCAL_MAC_ADDRESS.toString());
        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        assertEquals(TEST_LOCAL_MAC_ADDRESS.toString(), mWifiInfo.getMacAddress());
    }

    /**
     * Verify that we temporarily disable the network when auto-connected to a network
     * with no internet access.
     */
    @Test
    public void verifyAutoConnectedNetworkWithInternetValidationFailure() throws Exception {
        // Setup RSSI poll to update WifiInfo with low RSSI
        mCmi.enableRssiPolling(true);
        WifiLinkLayerStats llStats = new WifiLinkLayerStats();
        llStats.txmpdu_be = 1000;
        llStats.rxmpdu_bk = 2000;
        WifiNl80211Manager.SignalPollResult signalPollResult =
                new WifiNl80211Manager.SignalPollResult(RSSI_THRESHOLD_BREACH_MIN, 65, 54, sFreq);
        when(mWifiNative.getWifiLinkLayerStats(any())).thenReturn(llStats);
        when(mWifiNative.signalPoll(any())).thenReturn(signalPollResult);

        // Simulate the first connection.
        connect();
        ArgumentCaptor<INetworkAgent> agentCaptor = ArgumentCaptor.forClass(INetworkAgent.class);
        verify(mConnectivityManager).registerNetworkAgent(agentCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkAgentConfig.class), anyInt());

        WifiConfiguration currentNetwork = new WifiConfiguration();
        currentNetwork.networkId = FRAMEWORK_NETWORK_ID;
        currentNetwork.SSID = DEFAULT_TEST_SSID;
        currentNetwork.noInternetAccessExpected = false;
        currentNetwork.numNoInternetAccessReports = 1;
        when(mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(currentNetwork);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(FRAMEWORK_NETWORK_ID + 1);

        agentCaptor.getValue().onValidationStatusChanged(
                NetworkAgent.INVALID_NETWORK, null /* captivePortalUr; */);
        mLooper.dispatchAll();

        verify(mWifiConfigManager)
                .incrementNetworkNoInternetAccessReports(FRAMEWORK_NETWORK_ID);
        verify(mWifiConfigManager).updateNetworkSelectionStatus(
                FRAMEWORK_NETWORK_ID, DISABLED_NO_INTERNET_TEMPORARY);
        verify(mWifiBlocklistMonitor).handleBssidConnectionFailure(sBSSID, sSSID,
                WifiBlocklistMonitor.REASON_NETWORK_VALIDATION_FAILURE, RSSI_THRESHOLD_BREACH_MIN);
        verify(mWifiScoreCard).noteValidationFailure(any());
    }

    /**
     * Verify that we don't temporarily disable the network when user selected to connect to a
     * network with no internet access.
     */
    @Test
    public void verifyLastSelectedNetworkWithInternetValidationFailure() throws Exception {
        // Simulate the first connection.
        connect();
        ArgumentCaptor<INetworkAgent> agentCaptor = ArgumentCaptor.forClass(INetworkAgent.class);
        verify(mConnectivityManager).registerNetworkAgent(agentCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkAgentConfig.class), anyInt());

        WifiConfiguration currentNetwork = new WifiConfiguration();
        currentNetwork.networkId = FRAMEWORK_NETWORK_ID;
        currentNetwork.noInternetAccessExpected = false;
        currentNetwork.numNoInternetAccessReports = 1;
        when(mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(currentNetwork);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(FRAMEWORK_NETWORK_ID);

        agentCaptor.getValue().onValidationStatusChanged(
                NetworkAgent.INVALID_NETWORK, null /* captivePortalUrl */);
        mLooper.dispatchAll();

        verify(mWifiConfigManager)
                .incrementNetworkNoInternetAccessReports(FRAMEWORK_NETWORK_ID);
        verify(mWifiConfigManager, never()).updateNetworkSelectionStatus(
                FRAMEWORK_NETWORK_ID, DISABLED_NO_INTERNET_TEMPORARY);
    }

    /**
     * Verify that we temporarily disable the network when auto-connected to a network
     * with no internet access.
     */
    @Test
    public void verifyAutoConnectedNoInternetExpectedNetworkWithInternetValidationFailure()
            throws Exception {
        // Simulate the first connection.
        connect();
        ArgumentCaptor<INetworkAgent> agentCaptor = ArgumentCaptor.forClass(INetworkAgent.class);
        verify(mConnectivityManager).registerNetworkAgent(agentCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkAgentConfig.class), anyInt());

        WifiConfiguration currentNetwork = new WifiConfiguration();
        currentNetwork.networkId = FRAMEWORK_NETWORK_ID;
        currentNetwork.noInternetAccessExpected = true;
        currentNetwork.numNoInternetAccessReports = 1;
        when(mWifiConfigManager.getConfiguredNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(currentNetwork);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(FRAMEWORK_NETWORK_ID + 1);

        agentCaptor.getValue().onValidationStatusChanged(
                NetworkAgent.INVALID_NETWORK, null /* captivePortalUrl */);
        mLooper.dispatchAll();

        verify(mWifiConfigManager)
                .incrementNetworkNoInternetAccessReports(FRAMEWORK_NETWORK_ID);
        verify(mWifiConfigManager, never()).updateNetworkSelectionStatus(
                FRAMEWORK_NETWORK_ID, DISABLED_NO_INTERNET_TEMPORARY);
    }

    /**
     * Verify that we do not set the user connect choice after a successful connection if the
     * connection is not made by the user.
     */
    @Test
    public void testNonSettingsConnectionNotSetUserConnectChoice() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(false);
        connect();
        verify(mWifiBlocklistMonitor).handleBssidConnectionSuccess(sBSSID, sSSID);
        verify(mWifiConfigManager).updateNetworkAfterConnect(eq(FRAMEWORK_NETWORK_ID), eq(false),
                anyInt());
    }

    /**
     * Verify that we do not set the user connect choice after connecting to a newly added network.
     */
    @Test
    public void testNoSetUserConnectChoiceOnFirstConnection() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        connect();
        verify(mWifiBlocklistMonitor).handleBssidConnectionSuccess(sBSSID, sSSID);
        verify(mWifiConfigManager).updateNetworkAfterConnect(eq(FRAMEWORK_NETWORK_ID), eq(false),
                anyInt());
    }

    /**
     * Verify that on the second successful connection to a network we set the user connect choice.
     */
    @Test
    public void testConnectionSetUserConnectChoiceOnSecondConnection() throws Exception {
        when(mWifiPermissionsUtil.checkNetworkSettingsPermission(anyInt())).thenReturn(true);
        mTestNetworkParams.hasEverConnected = true;
        connect();
        verify(mWifiBlocklistMonitor).handleBssidConnectionSuccess(sBSSID, sSSID);
        verify(mWifiConfigManager).updateNetworkAfterConnect(eq(FRAMEWORK_NETWORK_ID), eq(true),
                anyInt());
    }

    /**
     * Verify that we enable the network when we detect validated internet access.
     */
    @Test
    public void verifyNetworkSelectionEnableOnInternetValidation() throws Exception {
        // Simulate the first connection.
        connect();
        verify(mWifiBlocklistMonitor).handleBssidConnectionSuccess(sBSSID, sSSID);
        verify(mWifiBlocklistMonitor).handleDhcpProvisioningSuccess(sBSSID, sSSID);
        verify(mWifiBlocklistMonitor, never()).handleNetworkValidationSuccess(sBSSID, sSSID);

        ArgumentCaptor<INetworkAgent> agentCaptor = ArgumentCaptor.forClass(INetworkAgent.class);
        verify(mConnectivityManager).registerNetworkAgent(agentCaptor.capture(),
                any(NetworkInfo.class), any(LinkProperties.class), any(NetworkCapabilities.class),
                anyInt(), any(NetworkAgentConfig.class), anyInt());

        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(FRAMEWORK_NETWORK_ID + 1);

        agentCaptor.getValue().onValidationStatusChanged(
                NetworkAgent.VALID_NETWORK, null /* captivePortalUrl */);
        mLooper.dispatchAll();

        verify(mWifiConfigManager)
                .setNetworkValidatedInternetAccess(FRAMEWORK_NETWORK_ID, true);
        verify(mWifiConfigManager).updateNetworkSelectionStatus(
                FRAMEWORK_NETWORK_ID, DISABLED_NONE);
        verify(mWifiScoreCard).noteValidationSuccess(any());
        verify(mWifiBlocklistMonitor).handleNetworkValidationSuccess(sBSSID, sSSID);
    }

    private void connectWithValidInitRssi(int initRssiDbm) throws Exception {
        triggerConnect();
        mWifiInfo.setRssi(initRssiDbm);
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.ASSOCIATED));
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("L3ProvisioningState", getCurrentState().getName());

        DhcpResultsParcelable dhcpResults = new DhcpResultsParcelable();
        dhcpResults.baseConfiguration = new StaticIpConfiguration();
        dhcpResults.baseConfiguration.gateway = InetAddresses.parseNumericAddress("1.2.3.4");
        dhcpResults.baseConfiguration.ipAddress =
                new LinkAddress(InetAddresses.parseNumericAddress("192.168.1.100"), 0);
        dhcpResults.baseConfiguration.dnsServers.add(InetAddresses.parseNumericAddress("8.8.8.8"));
        dhcpResults.leaseDuration = 3600;

        injectDhcpSuccess(dhcpResults);
        mLooper.dispatchAll();

    }

    /**
     * Verify that we set the INTERNET and bandwidth capability in the network agent when connected
     * as a result of auto-join/legacy API's. Also verify up/down stream bandwidth values when
     * Rx link speed is unavailable.
     */
    @Test
    public void verifyNetworkCapabilities() throws Exception {
        when(mWifiDataStall.getTxThroughputKbps()).thenReturn(70_000);
        when(mWifiDataStall.getRxThroughputKbps()).thenReturn(-1);
        when(mWifiNetworkFactory.getSpecificNetworkRequestUidAndPackageName(any()))
                .thenReturn(Pair.create(Process.INVALID_UID, ""));
        // Simulate the first connection.
        connectWithValidInitRssi(-42);

        ArgumentCaptor<NetworkCapabilities> networkCapabilitiesCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        verify(mConnectivityManager).registerNetworkAgent(any(),
                any(NetworkInfo.class), any(LinkProperties.class),
                networkCapabilitiesCaptor.capture(), anyInt(), any(NetworkAgentConfig.class),
                anyInt());

        NetworkCapabilities networkCapabilities = networkCapabilitiesCaptor.getValue();
        assertNotNull(networkCapabilities);

        // Should have internet capability.
        assertTrue(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        assertNull(networkCapabilities.getNetworkSpecifier());

        assertEquals(mConnectedNetwork.creatorUid, networkCapabilities.getOwnerUid());
        assertArrayEquals(
                new int[] {mConnectedNetwork.creatorUid},
                networkCapabilities.getAdministratorUids());

        // Should set bandwidth correctly
        assertEquals(-42, mWifiInfo.getRssi());
        assertEquals(70_000, networkCapabilities.getLinkUpstreamBandwidthKbps());
        assertEquals(70_000, networkCapabilities.getLinkDownstreamBandwidthKbps());
    }

    /**
     * Verify that we don't set the INTERNET capability in the network agent when connected
     * as a result of the new network request API. Also verify up/down stream bandwidth values
     * when both Tx and Rx link speed are unavailable.
     */
    @Test
    public void verifyNetworkCapabilitiesForSpecificRequest() throws Exception {
        when(mWifiDataStall.getTxThroughputKbps()).thenReturn(-1);
        when(mWifiDataStall.getRxThroughputKbps()).thenReturn(-1);
        when(mWifiNetworkFactory.getSpecificNetworkRequestUidAndPackageName(any()))
                .thenReturn(Pair.create(TEST_UID, OP_PACKAGE_NAME));
        // Simulate the first connection.
        connectWithValidInitRssi(-42);
        ArgumentCaptor<NetworkCapabilities> networkCapabilitiesCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        verify(mConnectivityManager).registerNetworkAgent(any(),
                any(NetworkInfo.class), any(LinkProperties.class),
                networkCapabilitiesCaptor.capture(), anyInt(), any(NetworkAgentConfig.class),
                anyInt());

        NetworkCapabilities networkCapabilities = networkCapabilitiesCaptor.getValue();
        assertNotNull(networkCapabilities);

        // should not have internet capability.
        assertFalse(networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));

        NetworkSpecifier networkSpecifier = networkCapabilities.getNetworkSpecifier();
        assertTrue(networkSpecifier instanceof WifiNetworkAgentSpecifier);
        WifiNetworkAgentSpecifier wifiNetworkAgentSpecifier =
                (WifiNetworkAgentSpecifier) networkSpecifier;
        WifiNetworkAgentSpecifier expectedWifiNetworkAgentSpecifier =
                new WifiNetworkAgentSpecifier(mCmi.getConnectedWifiConfiguration());
        assertEquals(expectedWifiNetworkAgentSpecifier, wifiNetworkAgentSpecifier);
        assertEquals(TEST_UID, networkCapabilities.getRequestorUid());
        assertEquals(OP_PACKAGE_NAME, networkCapabilities.getRequestorPackageName());
        assertEquals(90_000, networkCapabilities.getLinkUpstreamBandwidthKbps());
        assertEquals(80_000, networkCapabilities.getLinkDownstreamBandwidthKbps());
    }

    /**
     * Verify that we check for data stall during rssi poll
     * and then check that wifi link layer usage data are being updated.
     */
    @Test
    public void verifyRssiPollChecksDataStall() throws Exception {
        mCmi.enableRssiPolling(true);
        connect();

        WifiLinkLayerStats oldLLStats = new WifiLinkLayerStats();
        when(mWifiNative.getWifiLinkLayerStats(any())).thenReturn(oldLLStats);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL, 1);
        mLooper.dispatchAll();
        WifiLinkLayerStats newLLStats = new WifiLinkLayerStats();
        when(mWifiNative.getWifiLinkLayerStats(any())).thenReturn(newLLStats);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL, 1);
        mLooper.dispatchAll();
        verify(mWifiDataStall).checkDataStallAndThroughputSufficiency(
                oldLLStats, newLLStats, mWifiInfo);
        verify(mWifiMetrics).incrementWifiLinkLayerUsageStats(newLLStats);
    }

    /**
     * Verify that we update wifi usability stats entries during rssi poll and that when we get
     * a data stall we label and save the current list of usability stats entries.
     * @throws Exception
     */
    @Test
    public void verifyRssiPollUpdatesWifiUsabilityMetrics() throws Exception {
        mCmi.enableRssiPolling(true);
        connect();

        WifiLinkLayerStats stats = new WifiLinkLayerStats();
        when(mWifiNative.getWifiLinkLayerStats(any())).thenReturn(stats);
        when(mWifiDataStall.checkDataStallAndThroughputSufficiency(any(), any(), any()))
                .thenReturn(WifiIsUnusableEvent.TYPE_UNKNOWN);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL, 1);
        mLooper.dispatchAll();
        verify(mWifiMetrics).updateWifiUsabilityStatsEntries(any(), eq(stats));
        verify(mWifiMetrics, never()).addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_BAD,
                eq(anyInt()), eq(-1));

        when(mWifiDataStall.checkDataStallAndThroughputSufficiency(any(), any(), any()))
                .thenReturn(WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX);
        when(mClock.getElapsedSinceBootMillis()).thenReturn(10L);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL, 1);
        mLooper.dispatchAll();
        verify(mWifiMetrics, times(2)).updateWifiUsabilityStatsEntries(any(), eq(stats));
        when(mClock.getElapsedSinceBootMillis())
                .thenReturn(10L + ClientModeImpl.DURATION_TO_WAIT_ADD_STATS_AFTER_DATA_STALL_MS);
        mCmi.sendMessage(ClientModeImpl.CMD_RSSI_POLL, 1);
        mLooper.dispatchAll();
        verify(mWifiMetrics).addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_BAD,
                WifiIsUnusableEvent.TYPE_DATA_STALL_BAD_TX, -1);
    }

    /**
     * Verify that when ordered to setPowerSave(true) while Interface is created,
     * WifiNative is called with the right powerSave mode.
     */
    @Test
    public void verifySetPowerSaveTrueSuccess() throws Exception {
        // called once during setup()
        verify(mWifiNative).setPowerSave(WIFI_IFACE_NAME, true);

        assertTrue(mCmi.setPowerSave(true));
        verify(mWifiNative, times(2)).setPowerSave(WIFI_IFACE_NAME, true);
    }

    /**
     * Verify that when ordered to setPowerSave(false) while Interface is created,
     * WifiNative is called with the right powerSave mode.
     */
    @Test
    public void verifySetPowerSaveFalseSuccess() throws Exception {
        assertTrue(mCmi.setPowerSave(false));
        verify(mWifiNative).setPowerSave(WIFI_IFACE_NAME, false);
    }

    /**
     * Verify that we call into WifiTrafficPoller during rssi poll
     */
    @Test
    public void verifyRssiPollCallsWifiTrafficPoller() throws Exception {
        mCmi.enableRssiPolling(true);
        connect();

        verify(mWifiTrafficPoller).notifyOnDataActivity(anyLong(), anyLong());
    }

    /**
     * Verify that LinkProbeManager is updated during RSSI poll
     */
    @Test
    public void verifyRssiPollCallsLinkProbeManager() throws Exception {
        mCmi.enableRssiPolling(true);

        connect();
        // reset() should be called when RSSI polling is enabled and entering L2L3ConnectedState
        verify(mLinkProbeManager).resetOnNewConnection(); // called first time here
        verify(mLinkProbeManager, never()).resetOnScreenTurnedOn(); // not called
        verify(mLinkProbeManager).updateConnectionStats(any(), any());

        mCmi.enableRssiPolling(false);
        mLooper.dispatchAll();
        // reset() should be called when in L2L3ConnectedState (or child states) and RSSI polling
        // becomes enabled
        mCmi.enableRssiPolling(true);
        mLooper.dispatchAll();
        verify(mLinkProbeManager, times(1)).resetOnNewConnection(); // verify not called again
        verify(mLinkProbeManager).resetOnScreenTurnedOn(); // verify called here
    }

    /**
     * Verify that when ordered to setLowLatencyMode(true),
     * WifiNative is called with the right lowLatency mode.
     */
    @Test
    public void verifySetLowLatencyTrueSuccess() throws Exception {
        when(mWifiNative.setLowLatencyMode(anyBoolean())).thenReturn(true);
        assertTrue(mCmi.setLowLatencyMode(true));
        verify(mWifiNative).setLowLatencyMode(true);
    }

    /**
     * Verify that when ordered to setLowLatencyMode(false),
     * WifiNative is called with the right lowLatency mode.
     */
    @Test
    public void verifySetLowLatencyFalseSuccess() throws Exception {
        when(mWifiNative.setLowLatencyMode(anyBoolean())).thenReturn(true);
        assertTrue(mCmi.setLowLatencyMode(false));
        verify(mWifiNative).setLowLatencyMode(false);
    }

    /**
     * Verify that when WifiNative fails to set low latency mode,
     * then the call setLowLatencyMode() returns with failure,
     */
    @Test
    public void verifySetLowLatencyModeFailure() throws Exception {
        final boolean lowLatencyMode = true;
        when(mWifiNative.setLowLatencyMode(anyBoolean())).thenReturn(false);
        assertFalse(mCmi.setLowLatencyMode(lowLatencyMode));
        verify(mWifiNative).setLowLatencyMode(eq(lowLatencyMode));
    }

    /**
     * Verify getting the factory MAC address success case.
     */
    @Test
    public void testGetFactoryMacAddressSuccess() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        assertEquals(TEST_GLOBAL_MAC_ADDRESS.toString(), mCmi.getFactoryMacAddress());
        verify(mWifiNative).getStaFactoryMacAddress(WIFI_IFACE_NAME);
        verify(mWifiNative).getMacAddress(anyString()); // called once when setting up client mode
    }

    /**
     * Verify getting the factory MAC address failure case.
     */
    @Test
    public void testGetFactoryMacAddressFail() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        when(mWifiNative.getStaFactoryMacAddress(WIFI_IFACE_NAME)).thenReturn(null);
        assertNull(mCmi.getFactoryMacAddress());
        verify(mWifiNative).getStaFactoryMacAddress(WIFI_IFACE_NAME);
        verify(mWifiNative).getMacAddress(anyString()); // called once when setting up client mode
    }

    /**
     * Verify that when WifiNative#getStaFactoryMacAddress fails, if the device does not support
     * MAC randomization then the currently programmed MAC address gets returned.
     */
    @Test
    public void testGetFactoryMacAddressFailWithNoMacRandomizationSupport() throws Exception {
        // reset mWifiNative since initializeCmi() was called in setup()
        resetWifiNative();

        when(mWifiGlobals.isConnectedMacRandomizationEnabled()).thenReturn(false);
        initializeCmi();
        initializeAndAddNetworkAndVerifySuccess();
        when(mWifiNative.getStaFactoryMacAddress(WIFI_IFACE_NAME)).thenReturn(null);
        mCmi.getFactoryMacAddress();
        verify(mWifiNative).getStaFactoryMacAddress(anyString());
        verify(mWifiNative, times(2)).getMacAddress(WIFI_IFACE_NAME);
    }

    /**
     * Verify the MAC address is being randomized at start to prevent leaking the factory MAC.
     */
    @Test
    public void testRandomizeMacAddressOnStart() throws Exception {
        ArgumentCaptor<MacAddress> macAddressCaptor = ArgumentCaptor.forClass(MacAddress.class);
        verify(mWifiNative).setStaMacAddress(anyString(), macAddressCaptor.capture());
        MacAddress currentMac = macAddressCaptor.getValue();

        assertNotEquals("The currently programmed MAC address should be different from the factory "
                + "MAC address after ClientModeImpl starts",
                mCmi.getFactoryMacAddress(), currentMac.toString());
    }

    /**
     * Verify the MAC address is being randomized at start to prevent leaking the factory MAC.
     */
    @Test
    public void testNoRandomizeMacAddressOnStartIfMacRandomizationNotEnabled() throws Exception {
        // reset mWifiNative since initializeCmi() was called in setup()
        resetWifiNative();

        when(mWifiGlobals.isConnectedMacRandomizationEnabled()).thenReturn(false);
        initializeCmi();
        verify(mWifiNative, never()).setStaMacAddress(anyString(), any());
    }

    /**
     * Verify bugreport will be taken when get IP_REACHABILITY_LOST
     */
    @Test
    public void testTakebugreportbyIpReachabilityLost() throws Exception {
        connect();

        mCmi.sendMessage(ClientModeImpl.CMD_IP_REACHABILITY_LOST);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).triggerBugReportDataCapture(
                eq(WifiDiagnostics.REPORT_REASON_REACHABILITY_LOST));
    }

    /**
     * Verifies that WifiLastResortWatchdog is notified of FOURWAY_HANDSHAKE_TIMEOUT.
     */
    @Test
    public void testHandshakeTimeoutUpdatesWatchdog() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        // Verifies that WifiLastResortWatchdog won't be notified
        // by other reason code
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 2, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
        verify(mWifiLastResortWatchdog, never()).noteConnectionFailureAndTriggerIfNeeded(
                sSSID, sBSSID, WifiLastResortWatchdog.FAILURE_CODE_AUTHENTICATION);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        // Verifies that WifiLastResortWatchdog be notified
        // for FOURWAY_HANDSHAKE_TIMEOUT.
        disconnectEventInfo = new DisconnectEventInfo(sSSID, sBSSID, 15, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
        verify(mWifiLastResortWatchdog).noteConnectionFailureAndTriggerIfNeeded(
                sSSID, sBSSID, WifiLastResortWatchdog.FAILURE_CODE_AUTHENTICATION);

    }

    /**
     * Verify that WifiInfo is correctly populated after connection.
     */
    @Test
    public void verifyWifiInfoGetNetworkSpecifierPackageName() throws Exception {
        mConnectedNetwork.fromWifiNetworkSpecifier = true;
        mConnectedNetwork.ephemeral = true;
        mConnectedNetwork.trusted = true;
        mConnectedNetwork.creatorName = OP_PACKAGE_NAME;
        connect();

        assertTrue(mWifiInfo.isEphemeral());
        assertTrue(mWifiInfo.isTrusted());
        assertEquals(OP_PACKAGE_NAME,
                mWifiInfo.getRequestingPackageName());
    }

    /**
     * Verify that WifiInfo is correctly populated after connection.
     */
    @Test
    public void verifyWifiInfoGetNetworkSuggestionPackageName() throws Exception {
        mConnectedNetwork.fromWifiNetworkSuggestion = true;
        mConnectedNetwork.ephemeral = true;
        mConnectedNetwork.trusted = true;
        mConnectedNetwork.creatorName = OP_PACKAGE_NAME;
        connect();

        assertTrue(mWifiInfo.isEphemeral());
        assertTrue(mWifiInfo.isTrusted());
        assertEquals(OP_PACKAGE_NAME,
                mWifiInfo.getRequestingPackageName());
    }

    /**
     * Verify that a WifiIsUnusableEvent is logged and the current list of usability stats entries
     * are labeled and saved when receiving an IP reachability lost message.
     * @throws Exception
     */
    @Test
    public void verifyIpReachabilityLostMsgUpdatesWifiUsabilityMetrics() throws Exception {
        connect();

        mCmi.sendMessage(ClientModeImpl.CMD_IP_REACHABILITY_LOST);
        mLooper.dispatchAll();
        verify(mWifiMetrics).logWifiIsUnusableEvent(
                WifiIsUnusableEvent.TYPE_IP_REACHABILITY_LOST);
        verify(mWifiMetrics).addToWifiUsabilityStatsList(WifiUsabilityStats.LABEL_BAD,
                WifiUsabilityStats.TYPE_IP_REACHABILITY_LOST, -1);
    }

    /**
     * Tests that when {@link ClientModeImpl} receives a SUP_REQUEST_IDENTITY message, it responds
     * to the supplicant with the SIM identity.
     */
    @Test
    public void testSupRequestIdentity_setsIdentityResponse() throws Exception {
        mConnectedNetwork = spy(WifiConfigurationTestUtil.createEapNetwork(
                WifiEnterpriseConfig.Eap.SIM, WifiEnterpriseConfig.Phase2.NONE));
        mConnectedNetwork.SSID = DEFAULT_TEST_SSID;

        when(mDataTelephonyManager.getSubscriberId()).thenReturn("3214561234567890");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        when(mDataTelephonyManager.getSimOperator()).thenReturn("321456");
        when(mDataTelephonyManager.getCarrierInfoForImsiEncryption(anyInt())).thenReturn(null);
        MockitoSession mockSession = ExtendedMockito.mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        when(SubscriptionManager.getDefaultDataSubscriptionId()).thenReturn(DATA_SUBID);
        when(SubscriptionManager.isValidSubscriptionId(anyInt())).thenReturn(true);

        triggerConnect();

        mCmi.sendMessage(WifiMonitor.SUP_REQUEST_IDENTITY,
                0, FRAMEWORK_NETWORK_ID, DEFAULT_TEST_SSID);
        mLooper.dispatchAll();

        verify(mWifiNative).simIdentityResponse(WIFI_IFACE_NAME,
                "13214561234567890@wlan.mnc456.mcc321.3gppnetwork.org", "");
        mockSession.finishMocking();
    }

    /**
     * Verifies that WifiLastResortWatchdog is notified of DHCP failures when recevied
     * NETWORK_DISCONNECTION_EVENT while in L3ProvisioningState.
     */
    @Test
    public void testDhcpFailureUpdatesWatchdog_WhenDisconnectedWhileObtainingIpAddr()
            throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        startConnectSuccess();

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("L3ProvisioningState", getCurrentState().getName());

        // Verifies that WifiLastResortWatchdog be notified.
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
        verify(mWifiLastResortWatchdog).noteConnectionFailureAndTriggerIfNeeded(
                sSSID, sBSSID, WifiLastResortWatchdog.FAILURE_CODE_DHCP);
    }

    /**
     * Verifies that we trigger a disconnect when the {@link WifiConfigManager}.
     * OnNetworkUpdateListener#onNetworkRemoved(WifiConfiguration)} is invoked.
     */
    @Test
    public void testOnNetworkRemoved() throws Exception {
        connect();

        WifiConfiguration removedNetwork = new WifiConfiguration();
        removedNetwork.networkId = FRAMEWORK_NETWORK_ID;
        mConfigUpdateListenerCaptor.getValue().onNetworkRemoved(removedNetwork);
        mLooper.dispatchAll();

        verify(mWifiNative).removeNetworkCachedData(FRAMEWORK_NETWORK_ID);
        verify(mWifiNative).disconnect(WIFI_IFACE_NAME);
    }

    /**
     * Verifies that we trigger a disconnect when the {@link WifiConfigManager
     * .OnNetworkUpdateListener#onNetworkPermanentlyDisabled(WifiConfiguration, int)} is invoked.
     */
    @Test
    public void testOnNetworkPermanentlyDisabled() throws Exception {
        connect();

        WifiConfiguration disabledNetwork = new WifiConfiguration();
        disabledNetwork.networkId = FRAMEWORK_NETWORK_ID;
        mConfigUpdateListenerCaptor.getValue().onNetworkPermanentlyDisabled(disabledNetwork,
                WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD);
        mLooper.dispatchAll();

        verify(mWifiNative).disconnect(WIFI_IFACE_NAME);
    }

    /**
     * Verifies that we don't trigger a disconnect when the {@link WifiConfigManager
     * .OnNetworkUpdateListener#onNetworkPermanentlyDisabled(WifiConfiguration, int)} is invoked.
     */
    @Test
    public void testOnNetworkPermanentlyDisabledWithNoInternet() throws Exception {
        connect();

        WifiConfiguration disabledNetwork = new WifiConfiguration();
        disabledNetwork.networkId = FRAMEWORK_NETWORK_ID;
        mConfigUpdateListenerCaptor.getValue().onNetworkPermanentlyDisabled(disabledNetwork,
                WifiConfiguration.NetworkSelectionStatus.DISABLED_NO_INTERNET_PERMANENT);
        mLooper.dispatchAll();

        assertEquals("L3ConnectedState", getCurrentState().getName());
    }

    /**
     * Verifies that we don't trigger a disconnect when the {@link WifiConfigManager
     * .OnNetworkUpdateListener#onNetworkTemporarilyDisabled(WifiConfiguration, int)} is invoked.
     */
    @Test
    public void testOnNetworkTemporarilyDisabledWithNoInternet() throws Exception {
        connect();

        WifiConfiguration disabledNetwork = new WifiConfiguration();
        disabledNetwork.networkId = FRAMEWORK_NETWORK_ID;
        mConfigUpdateListenerCaptor.getValue().onNetworkTemporarilyDisabled(disabledNetwork,
                WifiConfiguration.NetworkSelectionStatus.DISABLED_NO_INTERNET_TEMPORARY);
        mLooper.dispatchAll();

        assertEquals("L3ConnectedState", getCurrentState().getName());
    }

    /**
     * Verify that MboOce/WifiDataStall enable/disable methods are called in ClientMode.
     */
    @Test
    public void verifyMboOceWifiDataStallSetupInClientMode() throws Exception {
        verify(mMboOceController).enable();
        verify(mWifiDataStall).enablePhoneStateListener();
        mCmi.stop();
        mLooper.dispatchAll();
        verify(mMboOceController).disable();
        verify(mWifiDataStall).disablePhoneStateListener();
    }

    @Test
    public void verifyWifiMonitorHandlersDeregisteredOnStop() throws Exception {
        verify(mWifiMonitor, atLeastOnce())
                .registerHandler(eq(WIFI_IFACE_NAME), anyInt(), any());
        verify(mWifiMetrics).registerForWifiMonitorEvents(WIFI_IFACE_NAME);
        verify(mWifiLastResortWatchdog).registerForWifiMonitorEvents(WIFI_IFACE_NAME);

        mCmi.stop();
        mLooper.dispatchAll();

        verify(mWifiMonitor, atLeastOnce())
                .deregisterHandler(eq(WIFI_IFACE_NAME), anyInt(), any());
        verify(mWifiMetrics).deregisterForWifiMonitorEvents(WIFI_IFACE_NAME);
        verify(mWifiLastResortWatchdog).deregisterForWifiMonitorEvents(WIFI_IFACE_NAME);
    }

    @Test
    public void onBluetoothConnectionStateChanged() throws Exception {
        // reset mWifiNative since initializeCmi() was called in setup()
        resetWifiNative();

        when(mWifiGlobals.isBluetoothConnected()).thenReturn(false);
        initializeCmi();
        verify(mWifiNative).setBluetoothCoexistenceScanMode(any(), eq(false));

        when(mWifiGlobals.isBluetoothConnected()).thenReturn(true);
        mCmi.onBluetoothConnectionStateChanged();
        mLooper.dispatchAll();
        verify(mWifiNative).setBluetoothCoexistenceScanMode(any(), eq(true));

        when(mWifiGlobals.isBluetoothConnected()).thenReturn(false);
        mCmi.onBluetoothConnectionStateChanged();
        mLooper.dispatchAll();
        verify(mWifiNative, times(2)).setBluetoothCoexistenceScanMode(any(), eq(false));
    }

    /**
     * Test that handleBssTransitionRequest() blocklist the BSS upon
     * receiving BTM request frame that contains MBO-OCE IE with an
     * association retry delay attribute.
     */
    @Test
    public void testBtmFrameWithMboAssocretryDelayBlockListTheBssid() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        MboOceController.BtmFrameData btmFrmData = new MboOceController.BtmFrameData();

        btmFrmData.mStatus = MboOceConstants.BTM_RESPONSE_STATUS_REJECT_UNSPECIFIED;
        btmFrmData.mBssTmDataFlagsMask = MboOceConstants.BTM_DATA_FLAG_DISASSOCIATION_IMMINENT
                | MboOceConstants.BTM_DATA_FLAG_MBO_ASSOC_RETRY_DELAY_INCLUDED;
        btmFrmData.mBlockListDurationMs = 60000;

        mCmi.sendMessage(WifiMonitor.MBO_OCE_BSS_TM_HANDLING_DONE, btmFrmData);
        mLooper.dispatchAll();

        verify(mWifiMetrics, times(1)).incrementSteeringRequestCountIncludingMboAssocRetryDelay();
        verify(mWifiBlocklistMonitor).blockBssidForDurationMs(eq(sBSSID), eq(sSSID),
                eq(btmFrmData.mBlockListDurationMs), anyInt(), anyInt());
        verify(mWifiConfigManager).setRecentFailureAssociationStatus(anyInt(),
                eq(WifiConfiguration.RECENT_FAILURE_MBO_OCE_DISCONNECT));
    }

    /**
     * Test that handleBssTransitionRequest() blocklist the BSS upon
     * receiving BTM request frame that contains disassociation imminent bit
     * set to 1.
     */
    @Test
    public void testBtmFrameWithDisassocImminentBitBlockListTheBssid() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        MboOceController.BtmFrameData btmFrmData = new MboOceController.BtmFrameData();

        btmFrmData.mStatus = MboOceConstants.BTM_RESPONSE_STATUS_ACCEPT;
        btmFrmData.mBssTmDataFlagsMask = MboOceConstants.BTM_DATA_FLAG_DISASSOCIATION_IMMINENT;

        mCmi.sendMessage(WifiMonitor.MBO_OCE_BSS_TM_HANDLING_DONE, btmFrmData);
        mLooper.dispatchAll();

        verify(mWifiMetrics, never()).incrementSteeringRequestCountIncludingMboAssocRetryDelay();
        verify(mWifiBlocklistMonitor).blockBssidForDurationMs(eq(sBSSID), eq(sSSID),
                eq(MboOceConstants.DEFAULT_BLOCKLIST_DURATION_MS), anyInt(), anyInt());
    }

    /**
     * Test that handleBssTransitionRequest() trigger force scan for
     * network selection when status code is REJECT.
     */
    @Test
    public void testBTMRequestRejectTriggerNetworkSelction() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        MboOceController.BtmFrameData btmFrmData = new MboOceController.BtmFrameData();

        btmFrmData.mStatus = MboOceConstants.BTM_RESPONSE_STATUS_REJECT_UNSPECIFIED;
        btmFrmData.mBssTmDataFlagsMask = MboOceConstants.BTM_DATA_FLAG_DISASSOCIATION_IMMINENT
                | MboOceConstants.BTM_DATA_FLAG_BSS_TERMINATION_INCLUDED
                | MboOceConstants.BTM_DATA_FLAG_MBO_CELL_DATA_CONNECTION_PREFERENCE_INCLUDED;
        btmFrmData.mBlockListDurationMs = 60000;

        mCmi.sendMessage(WifiMonitor.MBO_OCE_BSS_TM_HANDLING_DONE, btmFrmData);
        mLooper.dispatchAll();

        verify(mWifiBlocklistMonitor, never()).blockBssidForDurationMs(eq(sBSSID), eq(sSSID),
                eq(btmFrmData.mBlockListDurationMs), anyInt(), anyInt());
        verify(mWifiConnectivityManager).forceConnectivityScan(ClientModeImpl.WIFI_WORK_SOURCE);
        verify(mWifiMetrics, times(1)).incrementMboCellularSwitchRequestCount();
        verify(mWifiMetrics, times(1))
                .incrementForceScanCountDueToSteeringRequest();

    }

    /**
     * Test that handleBssTransitionRequest() does not trigger force
     * scan when status code is accept.
     */
    @Test
    public void testBTMRequestAcceptDoNotTriggerNetworkSelction() throws Exception {
        // Connect to network with |sBSSID|, |sFreq|.
        connect();

        MboOceController.BtmFrameData btmFrmData = new MboOceController.BtmFrameData();

        btmFrmData.mStatus = MboOceConstants.BTM_RESPONSE_STATUS_ACCEPT;
        btmFrmData.mBssTmDataFlagsMask = MboOceConstants.BTM_DATA_FLAG_DISASSOCIATION_IMMINENT;

        mCmi.sendMessage(WifiMonitor.MBO_OCE_BSS_TM_HANDLING_DONE, btmFrmData);
        mLooper.dispatchAll();

        verify(mWifiConnectivityManager, never())
                .forceConnectivityScan(ClientModeImpl.WIFI_WORK_SOURCE);
    }

    /**
     * Verifies that the logic does not modify PSK key management when WPA3 auto upgrade feature is
     * disabled.
     *
     * @throws Exception
     */
    @Test
    public void testNoWpa3UpgradeWhenOverlaysAreOff() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        WifiConfiguration config = mock(WifiConfiguration.class);
        config.SSID = sSSID;
        BitSet allowedKeyManagement = mock(BitSet.class);
        config.allowedKeyManagement = allowedKeyManagement;
        when(config.allowedKeyManagement.get(eq(WifiConfiguration.KeyMgmt.WPA_PSK))).thenReturn(
                true);
        when(config.getNetworkSelectionStatus())
                .thenReturn(new WifiConfiguration.NetworkSelectionStatus());
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(0)).thenReturn(config);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeEnabled, false);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeOffloadEnabled, false);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(config, never()).setSecurityParams(eq(WifiConfiguration.SECURITY_TYPE_SAE));
    }

    /**
     * Verifies that the logic always enables SAE key management when WPA3 auto upgrade offload
     * feature is enabled.
     *
     * @throws Exception
     */
    @Test
    public void testWpa3UpgradeOffload() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(0)).thenReturn(config);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeEnabled, true);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeOffloadEnabled, true);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        assertTrue(config.isSecurityType(WifiConfiguration.SECURITY_TYPE_SAE));
    }

    /**
     * Verifies that the logic does not enable SAE key management when WPA3 auto upgrade feature is
     * enabled but no SAE candidate is available.
     *
     * @throws Exception
     */
    @Test
    public void testNoWpa3UpgradeWithPskCandidate() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        WifiConfiguration config = mock(WifiConfiguration.class);
        config.SSID = sSSID;
        BitSet allowedKeyManagement = mock(BitSet.class);
        config.allowedKeyManagement = allowedKeyManagement;
        when(config.allowedKeyManagement.get(eq(WifiConfiguration.KeyMgmt.WPA_PSK)))
                .thenReturn(true);
        WifiConfiguration.NetworkSelectionStatus networkSelectionStatus =
                mock(WifiConfiguration.NetworkSelectionStatus.class);
        when(config.getNetworkSelectionStatus()).thenReturn(networkSelectionStatus);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(0)).thenReturn(config);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeEnabled, true);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeOffloadEnabled, false);

        String ssid = "WPA2-Network";
        String caps = "[WPA2-FT/PSK+PSK][ESS][WPS]";
        ScanResult scanResult = new ScanResult(WifiSsid.createFromAsciiEncoded(ssid), ssid,
                "ab:cd:01:ef:45:89", 1245, 0, caps, -78, 2450, 1025, 22, 33, 20, 0,
                0, true);
        scanResult.informationElements = new ScanResult.InformationElement[]{
                createIE(ScanResult.InformationElement.EID_SSID,
                        ssid.getBytes(StandardCharsets.UTF_8))
        };
        when(networkSelectionStatus.getCandidate()).thenReturn(scanResult);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        verify(config, never()).setSecurityParams(eq(WifiConfiguration.SECURITY_TYPE_SAE));
    }

    /**
     * Verifies that the logic enables SAE key management when WPA3 auto upgrade feature is
     * enabled and an SAE candidate is available.
     *
     * @throws Exception
     */
    @Test
    public void testWpa3UpgradeWithSaeCandidate() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        WifiConfiguration config = spy(new WifiConfiguration());
        config.SSID = sSSID;
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        WifiConfiguration.NetworkSelectionStatus networkSelectionStatus =
                mock(WifiConfiguration.NetworkSelectionStatus.class);
        when(config.getNetworkSelectionStatus()).thenReturn(networkSelectionStatus);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(anyInt())).thenReturn(config);
        when(mWifiConfigManager.getScanDetailCacheForNetwork(anyInt())).thenReturn(null);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeEnabled, true);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeOffloadEnabled, false);

        final String ssid = "WPA3-Network";
        String caps = "[WPA2-FT/SAE+SAE][ESS][WPS]";
        ScanResult scanResult = new ScanResult(WifiSsid.createFromAsciiEncoded(ssid), ssid,
                "ab:cd:01:ef:45:89", 1245, 0, caps, -78, 2450, 1025, 22, 33, 20, 0,
                0, true);
        scanResult.informationElements = new ScanResult.InformationElement[]{
                createIE(ScanResult.InformationElement.EID_SSID,
                        ssid.getBytes(StandardCharsets.UTF_8))
        };
        when(networkSelectionStatus.getCandidate()).thenReturn(scanResult);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        assertTrue(config.isSecurityType(WifiConfiguration.SECURITY_TYPE_SAE));
    }

    /**
     * Verifies that the logic does not enable SAE key management when WPA3 auto upgrade feature is
     * enabled and an SAE candidate is available, while another WPA2 AP is in range.
     *
     * @throws Exception
     */
    @Test
    public void testWpa3UpgradeWithSaeCandidateAndPskApInRange() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        WifiConfiguration config = spy(new WifiConfiguration());
        config.SSID = sSSID;
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        WifiConfiguration.NetworkSelectionStatus networkSelectionStatus =
                mock(WifiConfiguration.NetworkSelectionStatus.class);
        when(config.getNetworkSelectionStatus()).thenReturn(networkSelectionStatus);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(anyInt())).thenReturn(config);
        when(mWifiConfigManager.getScanDetailCacheForNetwork(anyInt())).thenReturn(null);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeEnabled, true);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeOffloadEnabled, false);

        final String saeBssid = "ab:cd:01:ef:45:89";
        final String pskBssid = "ab:cd:01:ef:45:9a";

        final String ssidSae = "Mixed-Network";
        config.SSID = ScanResultUtil.createQuotedSSID(ssidSae);
        config.networkId = 1;

        String capsSae = "[WPA2-FT/SAE+SAE][ESS][WPS]";
        ScanResult scanResultSae = new ScanResult(WifiSsid.createFromAsciiEncoded(ssidSae), ssidSae,
                saeBssid, 1245, 0, capsSae, -78, 2412, 1025, 22, 33, 20, 0,
                0, true);
        ScanResult.InformationElement ieSae = createIE(ScanResult.InformationElement.EID_SSID,
                ssidSae.getBytes(StandardCharsets.UTF_8));
        scanResultSae.informationElements = new ScanResult.InformationElement[]{ieSae};
        when(networkSelectionStatus.getCandidate()).thenReturn(scanResultSae);
        ScanResult.InformationElement[] ieArr = new ScanResult.InformationElement[1];
        ieArr[0] = ieSae;

        final String ssidPsk = "Mixed-Network";
        String capsPsk = "[WPA2-FT/PSK+PSK][ESS][WPS]";
        ScanResult scanResultPsk = new ScanResult(WifiSsid.createFromAsciiEncoded(ssidPsk), ssidPsk,
                pskBssid, 1245, 0, capsPsk, -48, 2462, 1025, 22, 33, 20, 0,
                0, true);
        ScanResult.InformationElement iePsk = createIE(ScanResult.InformationElement.EID_SSID,
                ssidPsk.getBytes(StandardCharsets.UTF_8));
        scanResultPsk.informationElements = new ScanResult.InformationElement[]{iePsk};
        ScanResult.InformationElement[] ieArrPsk = new ScanResult.InformationElement[1];
        ieArrPsk[0] = iePsk;
        List<ScanResult> scanResults = new ArrayList<>();
        scanResults.add(scanResultPsk);
        scanResults.add(scanResultSae);

        when(mScanRequestProxy.getScanResults()).thenReturn(scanResults);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        assertFalse(config.isSecurityType(WifiConfiguration.SECURITY_TYPE_SAE));
    }

    /**
     * Verifies that the logic enables SAE key management when WPA3 auto upgrade feature is
     * enabled and no candidate is available (i.e. user selected a WPA2 saved network and the
     * network is actually WPA3.
     *
     * @throws Exception
     */
    @Test
    public void testWpa3UpgradeWithNoCandidate() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        WifiConfiguration config = spy(new WifiConfiguration());
        config.SSID = sSSID;
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        WifiConfiguration.NetworkSelectionStatus networkSelectionStatus =
                mock(WifiConfiguration.NetworkSelectionStatus.class);
        when(config.getNetworkSelectionStatus()).thenReturn(networkSelectionStatus);
        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(anyInt())).thenReturn(config);

        mResources.setBoolean(R.bool.config_wifiSaeUpgradeEnabled, true);
        mResources.setBoolean(R.bool.config_wifiSaeUpgradeOffloadEnabled, false);

        final String saeBssid = "ab:cd:01:ef:45:89";
        final String ssidSae = "WPA3-Network";
        config.SSID = ScanResultUtil.createQuotedSSID(ssidSae);
        config.networkId = 1;

        String capsSae = "[WPA2-FT/SAE+SAE][ESS][WPS]";
        ScanResult scanResultSae = new ScanResult(WifiSsid.createFromAsciiEncoded(ssidSae), ssidSae,
                saeBssid, 1245, 0, capsSae, -78, 2412, 1025, 22, 33, 20, 0,
                0, true);
        ScanResult.InformationElement ieSae = createIE(ScanResult.InformationElement.EID_SSID,
                ssidSae.getBytes(StandardCharsets.UTF_8));
        scanResultSae.informationElements = new ScanResult.InformationElement[]{ieSae};
        when(networkSelectionStatus.getCandidate()).thenReturn(null);
        List<ScanResult> scanResults = new ArrayList<>();
        scanResults.add(scanResultSae);

        when(mScanRequestProxy.getScanResults()).thenReturn(scanResults);

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        assertTrue(config.isSecurityType(WifiConfiguration.SECURITY_TYPE_SAE));
    }

    private static ScanResult.InformationElement createIE(int id, byte[] bytes) {
        ScanResult.InformationElement ie = new ScanResult.InformationElement();
        ie.id = id;
        ie.bytes = bytes;
        return ie;
    }

    /**
     * Helper function for setting up fils test.
     *
     * @param isDriverSupportFils true if driver support fils.
     * @return wifi configuration.
     */
    private WifiConfiguration setupFilsTest(boolean isDriverSupportFils) {
        WifiConfiguration config = new WifiConfiguration();
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_EAP);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
        config.SSID = ScanResultUtil.createQuotedSSID(sFilsSsid);
        config.networkId = 1;
        config.setRandomizedMacAddress(TEST_LOCAL_MAC_ADDRESS);
        config.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_AUTO;

        when(mWifiConfigManager.getConfiguredNetworkWithoutMasking(anyInt())).thenReturn(config);
        if (isDriverSupportFils) {
            when(mWifiNative.getSupportedFeatureSet(WIFI_IFACE_NAME)).thenReturn(
                    WifiManager.WIFI_FEATURE_FILS_SHA256 | WifiManager.WIFI_FEATURE_FILS_SHA384);
        } else {
            when(mWifiNative.getSupportedFeatureSet(WIFI_IFACE_NAME)).thenReturn((long) 0);
        }

        return config;
    }

    /**
     * Helper function for setting up a scan result with FILS supported AP.
     *
     */
    private void setupFilsEnabledApInScanResult() {
        String caps = "[WPA2-EAP+EAP-SHA256+EAP-FILS-SHA256-CCMP]"
                + "[RSN-EAP+EAP-SHA256+EAP-FILS-SHA256-CCMP][ESS]";
        ScanResult scanResult = new ScanResult(WifiSsid.createFromAsciiEncoded(sFilsSsid),
                sFilsSsid, sBSSID, 1245, 0, caps, -78, 2412, 1025, 22, 33, 20, 0, 0, true);
        ScanResult.InformationElement ie = createIE(ScanResult.InformationElement.EID_SSID,
                sFilsSsid.getBytes(StandardCharsets.UTF_8));
        scanResult.informationElements = new ScanResult.InformationElement[]{ie};
        when(mScanRequestProxy.getScanResults()).thenReturn(Arrays.asList(scanResult));
        when(mScanRequestProxy.getScanResult(eq(sBSSID))).thenReturn(scanResult);
    }


    /**
     * Helper function to send CMD_START_FILS_CONNECTION along with HLP IEs.
     *
     */
    private void prepareFilsHlpPktAndSendStartConnect() {
        Layer2PacketParcelable l2Packet = new Layer2PacketParcelable();
        l2Packet.dstMacAddress = TEST_GLOBAL_MAC_ADDRESS;
        l2Packet.payload = new byte[] {0x00, 0x12, 0x13, 0x00, 0x12, 0x13, 0x00, 0x12, 0x13,
                0x12, 0x13, 0x00, 0x12, 0x13, 0x00, 0x12, 0x13, 0x00, 0x12, 0x13, 0x55, 0x66};
        mCmi.sendMessage(ClientModeImpl.CMD_START_FILS_CONNECTION, 0, 0,
                Collections.singletonList(l2Packet));
        mLooper.dispatchAll();
    }

    /**
     * Verifies that while connecting to AP, the logic looks into the scan result and
     * looks for AP matching the network type and ssid and update the wificonfig with FILS
     * AKM if supported.
     *
     * @throws Exception
     */
    @Test
    public void testFilsAKMUpdateBeforeConnect() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        WifiConfiguration config = setupFilsTest(true);
        setupFilsEnabledApInScanResult();

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.FILS_SHA256));
        verify(mWifiNative, never()).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
    }

    /**
     * Verifies that while connecting to AP, framework updates the wifi config with
     * FILS AKM only if underlying driver support FILS feature.
     *
     * @throws Exception
     */
    @Test
    public void testFilsAkmIsNotAddedinWifiConfigIfDriverDoesNotSupportFils() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        WifiConfiguration config = setupFilsTest(false);
        setupFilsEnabledApInScanResult();

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        assertFalse(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.FILS_SHA256));
        verify(mWifiNative).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
    }


    /**
     * Verifies that the HLP (DHCP) packets are send to wpa_supplicant
     * prior to Fils connection.
     *
     * @throws Exception
     */
    @Test
    public void testFilsHlpUpdateBeforeFilsConnection() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        WifiConfiguration config = setupFilsTest(true);
        setupFilsEnabledApInScanResult();

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        prepareFilsHlpPktAndSendStartConnect();

        verify(mWifiNative).flushAllHlp(eq(WIFI_IFACE_NAME));
        verify(mWifiNative).addHlpReq(eq(WIFI_IFACE_NAME), any(), any());
        verify(mWifiNative).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
    }

    /**
     * Verifies that an association rejection in first FILS connect attempt doesn't block
     * the second connection attempt.
     *
     * @throws Exception
     */
    @Test
    public void testFilsSecondConnectAttemptIsNotBLockedAfterAssocReject() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        WifiConfiguration config = setupFilsTest(true);
        setupFilsEnabledApInScanResult();

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        prepareFilsHlpPktAndSendStartConnect();

        verify(mWifiNative, times(1)).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));

        mCmi.sendMessage(WifiMonitor.ASSOCIATION_REJECTION_EVENT,
                new AssocRejectEventInfo(sSSID, sBSSID, 2, false));
        mLooper.dispatchAll();

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        prepareFilsHlpPktAndSendStartConnect();

        verify(mWifiNative, times(2)).connectToNetwork(eq(WIFI_IFACE_NAME), eq(config));
    }

    /**
     * Verifies Fils connection.
     *
     * @throws Exception
     */
    @Test
    public void testFilsConnection() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();
        WifiConfiguration config = setupFilsTest(true);
        setupFilsEnabledApInScanResult();

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        prepareFilsHlpPktAndSendStartConnect();

        verify(mWifiMetrics, times(1)).incrementConnectRequestWithFilsAkmCount();

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 1, sBSSID);
        mLooper.dispatchAll();

        verify(mWifiMetrics, times(1)).incrementL2ConnectionThroughFilsAuthCount();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, WifiSsid.createFromAsciiEncoded(sFilsSsid),
                sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("L3ProvisioningState", getCurrentState().getName());

        DhcpResultsParcelable dhcpResults = new DhcpResultsParcelable();
        dhcpResults.baseConfiguration = new StaticIpConfiguration();
        dhcpResults.baseConfiguration.gateway = InetAddresses.parseNumericAddress("1.2.3.4");
        dhcpResults.baseConfiguration.ipAddress =
                new LinkAddress(InetAddresses.parseNumericAddress("192.168.1.100"), 0);
        dhcpResults.baseConfiguration.dnsServers.add(InetAddresses.parseNumericAddress("8.8.8.8"));
        dhcpResults.leaseDuration = 3600;

        injectDhcpSuccess(dhcpResults);
        mLooper.dispatchAll();

        WifiInfo wifiInfo = mWifiInfo;
        assertNotNull(wifiInfo);
        assertEquals(sBSSID, wifiInfo.getBSSID());
        assertTrue(WifiSsid.createFromAsciiEncoded(sFilsSsid).equals(wifiInfo.getWifiSsid()));
        assertEquals("L3ConnectedState", getCurrentState().getName());
    }

    /**
     * Tests the wifi info is updated correctly for connecting network.
     */
    @Test
    public void testWifiInfoOnConnectingNextNetwork() throws Exception {
        mConnectedNetwork.ephemeral = true;
        mConnectedNetwork.trusted = true;
        mConnectedNetwork.oemPaid = true;
        mConnectedNetwork.oemPrivate = true;
        mConnectedNetwork.carrierMerged = true;
        mConnectedNetwork.osu = true;
        mConnectedNetwork.subscriptionId = DATA_SUBID;

        triggerConnect();
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);

        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());

        // before the fist success connection, there is no valid wifi info.
        assertEquals(WifiConfiguration.INVALID_NETWORK_ID, mWifiInfo.getNetworkId());

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(FRAMEWORK_NETWORK_ID,
                    sWifiSsid, sBSSID, SupplicantState.ASSOCIATED));
        mLooper.dispatchAll();

        // retrieve correct wifi info on receiving the supplicant state change event.
        assertEquals(FRAMEWORK_NETWORK_ID, mWifiInfo.getNetworkId());
        assertEquals(mConnectedNetwork.ephemeral, mWifiInfo.isEphemeral());
        assertEquals(mConnectedNetwork.trusted, mWifiInfo.isTrusted());
        assertEquals(mConnectedNetwork.oemPaid, mWifiInfo.isOemPaid());
        assertEquals(mConnectedNetwork.oemPrivate, mWifiInfo.isOemPrivate());
        assertEquals(mConnectedNetwork.carrierMerged, mWifiInfo.isCarrierMerged());
        assertEquals(mConnectedNetwork.osu, mWifiInfo.isOsuAp());
        assertEquals(DATA_SUBID, mWifiInfo.getSubscriptionId());
    }

    /**
     * Verify that we disconnect when we mark a previous unmetered network metered.
     */
    @Test
    public void verifyDisconnectOnMarkingNetworkMetered() throws Exception {
        connect();
        expectRegisterNetworkAgent((config) -> { }, (cap) -> {
            assertTrue(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        });

        WifiConfiguration oldConfig = new WifiConfiguration(mConnectedNetwork);
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_METERED;

        mConfigUpdateListenerCaptor.getValue().onNetworkUpdated(mConnectedNetwork, oldConfig);
        mLooper.dispatchAll();
        verify(mWifiNative).disconnect(WIFI_IFACE_NAME);
    }

    /**
     * Verify that we only update capabilites when we mark a previous unmetered network metered.
     */
    @Test
    public void verifyUpdateCapabilitiesOnMarkingNetworkUnmetered() throws Exception {
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_METERED;
        connect();
        expectRegisterNetworkAgent((config) -> { }, (cap) -> {
            assertFalse(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        });
        reset(mNetworkAgentRegistry);

        WifiConfiguration oldConfig = new WifiConfiguration(mConnectedNetwork);
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_NOT_METERED;

        mConfigUpdateListenerCaptor.getValue().onNetworkUpdated(mConnectedNetwork, oldConfig);
        mLooper.dispatchAll();
        assertEquals("L3ConnectedState", getCurrentState().getName());

        expectNetworkAgentUpdateCapabilities((cap) -> {
            assertTrue(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        });
    }


    /**
     * Verify that we disconnect when we mark a previous unmetered network metered.
     */
    @Test
    public void verifyDisconnectOnMarkingNetworkAutoMeteredWithMeteredHint() throws Exception {
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_NOT_METERED;
        connect();
        expectRegisterNetworkAgent((config) -> { }, (cap) -> {
            assertTrue(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        });
        reset(mNetworkAgentRegistry);

        // Mark network metered none.
        WifiConfiguration oldConfig = new WifiConfiguration(mConnectedNetwork);
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_NONE;

        // Set metered hint in WifiInfo (either via DHCP or ScanResult IE).
        WifiInfo wifiInfo = mWifiInfo;
        wifiInfo.setMeteredHint(true);

        mConfigUpdateListenerCaptor.getValue().onNetworkUpdated(mConnectedNetwork, oldConfig);
        mLooper.dispatchAll();
        verify(mWifiNative).disconnect(WIFI_IFACE_NAME);
    }

    /**
     * Verify that we only update capabilites when we mark a previous unmetered network metered.
     */
    @Test
    public void verifyUpdateCapabilitiesOnMarkingNetworkAutoMeteredWithoutMeteredHint()
            throws Exception {
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_METERED;
        connect();
        expectRegisterNetworkAgent((config) -> { }, (cap) -> {
            assertFalse(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        });
        reset(mNetworkAgentRegistry);

        WifiConfiguration oldConfig = new WifiConfiguration(mConnectedNetwork);
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_NONE;

        // Reset metered hint in WifiInfo.
        WifiInfo wifiInfo = mWifiInfo;
        wifiInfo.setMeteredHint(false);

        mConfigUpdateListenerCaptor.getValue().onNetworkUpdated(mConnectedNetwork, oldConfig);
        mLooper.dispatchAll();
        assertEquals("L3ConnectedState", getCurrentState().getName());

        expectNetworkAgentUpdateCapabilities((cap) -> {
            assertTrue(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        });
    }

    /**
     * Verify that we do nothing on no metered change.
     */
    @Test
    public void verifyDoNothingMarkingNetworkAutoMeteredWithMeteredHint() throws Exception {
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_METERED;
        connect();
        expectRegisterNetworkAgent((config) -> { }, (cap) -> {
            assertFalse(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        });
        reset(mNetworkAgentRegistry);

        // Mark network metered none.
        WifiConfiguration oldConfig = new WifiConfiguration(mConnectedNetwork);
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_NONE;

        // Set metered hint in WifiInfo (either via DHCP or ScanResult IE).
        WifiInfo wifiInfo = mWifiInfo;
        wifiInfo.setMeteredHint(true);

        mConfigUpdateListenerCaptor.getValue().onNetworkUpdated(mConnectedNetwork, oldConfig);
        mLooper.dispatchAll();
        assertEquals("L3ConnectedState", getCurrentState().getName());

        verifyNoMoreInteractions(mNetworkAgentRegistry);
    }

    /**
     * Verify that we do nothing on no metered change.
     */
    @Test
    public void verifyDoNothingMarkingNetworkAutoMeteredWithoutMeteredHint() throws Exception {
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_NOT_METERED;
        connect();
        expectRegisterNetworkAgent((config) -> { }, (cap) -> {
            assertTrue(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        });
        reset(mNetworkAgentRegistry);

        // Mark network metered none.
        WifiConfiguration oldConfig = new WifiConfiguration(mConnectedNetwork);
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_NONE;

        // Reset metered hint in WifiInfo.
        WifiInfo wifiInfo = mWifiInfo;
        wifiInfo.setMeteredHint(false);

        mConfigUpdateListenerCaptor.getValue().onNetworkUpdated(mConnectedNetwork, oldConfig);
        mLooper.dispatchAll();
        assertEquals("L3ConnectedState", getCurrentState().getName());

        verifyNoMoreInteractions(mNetworkAgentRegistry);
    }

    /*
     * Verify that network cached data is cleared correctly in
     * disconnected state.
     */
    @Test
    public void testNetworkCachedDataIsClearedCorrectlyInDisconnectedState() throws Exception {
        // Setup CONNECT_MODE & a WifiConfiguration
        initializeAndAddNetworkAndVerifySuccess();
        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        // got UNSPECIFIED during this connection attempt
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 1, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
        verify(mWifiNative, never()).removeNetworkCachedData(anyInt());

        mCmi.sendMessage(ClientModeImpl.CMD_START_CONNECT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        // got 4WAY_HANDSHAKE_TIMEOUT during this connection attempt
        disconnectEventInfo = new DisconnectEventInfo(sSSID, sBSSID, 15, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        assertEquals("DisconnectedState", getCurrentState().getName());
        verify(mWifiNative).removeNetworkCachedData(FRAMEWORK_NETWORK_ID);
    }

    /*
     * Verify that network cached data is cleared correctly in
     * disconnected state.
     */
    @Test
    public void testNetworkCachedDataIsClearedCorrectlyInL3ProvisioningState() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        verify(mWifiNative).removeAllNetworks(WIFI_IFACE_NAME);

        startConnectSuccess();

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();

        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        assertEquals("L3ProvisioningState", getCurrentState().getName());

        // got 4WAY_HANDSHAKE_TIMEOUT during this connection attempt
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 15, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        verify(mWifiNative).removeNetworkCachedData(FRAMEWORK_NETWORK_ID);
    }

    /*
     * Verify that network cached data is NOT cleared in L3ConnectedState.
     */
    @Test
    public void testNetworkCachedDataIsClearedIf4WayHandshakeFailure() throws Exception {
        when(mWifiScoreCard.detectAbnormalDisconnection())
                .thenReturn(WifiHealthMonitor.REASON_SHORT_CONNECTION_NONLOCAL);
        InOrder inOrderWifiLockManager = inOrder(mWifiLockManager);
        connect();
        inOrderWifiLockManager.verify(mWifiLockManager)
                .updateWifiClientConnected(mClientModeManager, true);

        // got 4WAY_HANDSHAKE_TIMEOUT
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 15, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();
        verify(mWifiNative, never()).removeNetworkCachedData(anyInt());
    }

    /**
     * Verify that network cached data is cleared on updating a network.
     */
    @Test
    public void testNetworkCachedDataIsClearedOnUpdatingNetwork() throws Exception {
        WifiConfiguration oldConfig = new WifiConfiguration(mConnectedNetwork);
        mConnectedNetwork.meteredOverride = METERED_OVERRIDE_METERED;

        mConfigUpdateListenerCaptor.getValue().onNetworkUpdated(mConnectedNetwork, oldConfig);
        mLooper.dispatchAll();
        verify(mWifiNative).removeNetworkCachedData(eq(oldConfig.networkId));
    }


    @Test
    public void testVerifyWifiInfoStateOnFrameworkDisconnect() throws Exception {
        connect();

        assertEquals(mWifiInfo.getSupplicantState(), SupplicantState.COMPLETED);

        // Now trigger disconnect
        mCmi.disconnect();
        mLooper.dispatchAll();

        // get disconnect event
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, WifiSsid.createFromAsciiEncoded(mConnectedNetwork.SSID),
                        sBSSID, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        assertEquals(mWifiInfo.getSupplicantState(), SupplicantState.DISCONNECTED);
    }

    @Test
    public void testVerifyWifiInfoStateOnFrameworkDisconnectButMissingDisconnectEvent()
            throws Exception {
        connect();

        assertEquals(mWifiInfo.getSupplicantState(), SupplicantState.COMPLETED);

        // Now trigger disconnect
        mCmi.disconnect();
        mLooper.dispatchAll();

        // missing disconnect event, but got supplicant state change with disconnect state instead.
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, WifiSsid.createFromAsciiEncoded(mConnectedNetwork.SSID),
                        sBSSID, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        assertEquals(mWifiInfo.getSupplicantState(), SupplicantState.DISCONNECTED);
    }

    /**
     * Ensures that we only disable the current network & set MAC address only when we exit
     * ConnectingState.
     * @throws Exception
     */
    @Test
    public void testDisableNetworkOnExitingConnectingOrConnectedState() throws Exception {
        connect();
        String oldSsid = mConnectedNetwork.SSID;

        // Trigger connection to a different network
        mConnectedNetwork.SSID = oldSsid.concat("blah");
        mConnectedNetwork.networkId++;
        mConnectedNetwork.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_NONE;
        setupAndStartConnectSequence(mConnectedNetwork);

        // Send disconnect event for the old network.
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(oldSsid, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        assertEquals("L2ConnectingState", getCurrentState().getName());
        // Since we remain in connecting state, we should not disable the network or set random MAC
        // address on disconnect.
        verify(mWifiNative, never()).disableNetwork(WIFI_IFACE_NAME);
        // Set MAC address thrice - once at bootup, twice for the 2 connections.
        verify(mWifiNative, times(3)).setStaMacAddress(eq(WIFI_IFACE_NAME), any());

        // Send disconnect event for the new network.
        disconnectEventInfo =
                new DisconnectEventInfo(mConnectedNetwork.SSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        verify(mWifiNative).disableNetwork(WIFI_IFACE_NAME);
        // Set MAC address thrice - once at bootup, twice for the connections,
        // once for the disconnect.
        verify(mWifiNative, times(4)).setStaMacAddress(eq(WIFI_IFACE_NAME), any());
    }

    @Test
    public void testIpReachabilityLostAndRoamEventsRace() throws Exception {
        connect();
        expectRegisterNetworkAgent((agentConfig) -> { }, (cap) -> { });
        reset(mNetworkAgentRegistry);

        // Trigger ip reachability loss and ensure we trigger a disconnect.
        mCmi.sendMessage(ClientModeImpl.CMD_IP_REACHABILITY_LOST);
        mLooper.dispatchAll();
        verify(mWifiNative).disconnect(WIFI_IFACE_NAME);

        // Now send a network connection (indicating a roam) event before we get the disconnect
        // event.
        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        // ensure that we ignored the transient roam while we're disconnecting.
        verifyNoMoreInteractions(mNetworkAgentRegistry);

        // Now send the disconnect event and ensure that we transition to "DisconnectedState".
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(sSSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();
        assertEquals("DisconnectedState", getCurrentState().getName());
        expectUnregisterNetworkAgent();

        verifyNoMoreInteractions(mNetworkAgentRegistry);
    }

    @Test
    public void testConnectionWhileDisconnecting() throws Exception {
        connect();

        // Trigger a disconnect event.
        mCmi.disconnect();
        mLooper.dispatchAll();
        assertEquals("L3ConnectedState", getCurrentState().getName());

        // Trigger a new connection before the NETWORK_DISCONNECTION_EVENT comes in.
        WifiConfiguration config = WifiConfigurationTestUtil.createOpenNetwork();
        config.networkId = FRAMEWORK_NETWORK_ID + 1;
        setupAndStartConnectSequence(config);
        // Ensure that we triggered the connection attempt.
        validateSuccessfulConnectSequence(config);

        // Now trigger the disconnect event for the previous disconnect and ensure we handle it
        // correctly and remain in ConnectingState.
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(mConnectedNetwork.SSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();
        assertEquals("L2ConnectingState", mCmi.getCurrentState().getName());
    }

    @Test
    public void testConnectionWatchdog() throws Exception {
        triggerConnect();
        Log.i(TAG, "Triggering Connect done");

        // Simulate watchdog timeout and ensure we retuned to disconnected state.
        mLooper.moveTimeForward(ClientModeImpl.CONNECTING_WATCHDOG_TIMEOUT_MS + 5L);
        mLooper.dispatchAll();

        verify(mWifiNative).disableNetwork(WIFI_IFACE_NAME);
        assertEquals("DisconnectedState", mCmi.getCurrentState().getName());
    }

    @Test
    public void testRoamAfterConnectDoesNotChangeNetworkInfoInNetworkStateChangeBroadcast()
            throws Exception {
        connect();

        // The last NETWORK_STATE_CHANGED_ACTION should be to mark the network connected.
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(), any());
        Intent intent = intentCaptor.getValue();
        assertNotNull(intent);
        assertEquals(WifiManager.NETWORK_STATE_CHANGED_ACTION, intent.getAction());
        NetworkInfo networkInfo = (NetworkInfo) intent.getExtra(WifiManager.EXTRA_NETWORK_INFO);
        assertTrue(networkInfo.isConnected());

        reset(mContext);

        // send roam event
        mCmi.sendMessage(WifiMonitor.ASSOCIATED_BSSID_EVENT, 0, 0, sBSSID1);
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID1, SupplicantState.COMPLETED));
        mLooper.dispatchAll();

        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(), any());
        intent = intentCaptor.getValue();
        assertNotNull(intent);
        assertEquals(WifiManager.NETWORK_STATE_CHANGED_ACTION, intent.getAction());
        networkInfo = (NetworkInfo) intent.getExtra(WifiManager.EXTRA_NETWORK_INFO);
        assertTrue(networkInfo.isConnected());
    }


    /**
     * Ensure that {@link ClientModeImpl#dump(FileDescriptor, PrintWriter, String[])}
     * {@link WifiNative#getWifiLinkLayerStats(String)}, at least once before calling
     * {@link WifiScoreReport#dump(FileDescriptor, PrintWriter, String[])}.
     *
     * This ensures that WifiScoreReport will always get updated RSSI and link layer stats before
     * dumping during a bug report, no matter if the screen is on or not.
     */
    @Test
    public void testWifiScoreReportDump() throws Exception {
        connect();

        mLooper.startAutoDispatch();
        mCmi.dump(new FileDescriptor(), new PrintWriter(new StringWriter()), null);
        mLooper.stopAutoDispatchAndIgnoreExceptions();

        InOrder inOrder = inOrder(mWifiNative, mWifiScoreReport);

        inOrder.verify(mWifiNative).getWifiLinkLayerStats(any());
        inOrder.verify(mWifiScoreReport).dump(any(), any(), any());
    }

    @Test
    public void clearRequestingPackageNameInWifiInfoOnConnectionFailure() throws Exception {
        mConnectedNetwork.fromWifiNetworkSpecifier = true;
        mConnectedNetwork.ephemeral = true;
        mConnectedNetwork.creatorName = OP_PACKAGE_NAME;

        triggerConnect();

        // association completed
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.ASSOCIATED));
        mLooper.dispatchAll();

        assertTrue(mWifiInfo.isEphemeral());
        assertEquals(OP_PACKAGE_NAME, mWifiInfo.getRequestingPackageName());

        // fail the connection.
        DisconnectEventInfo disconnectEventInfo =
                new DisconnectEventInfo(mConnectedNetwork.SSID, sBSSID, 0, false);
        mCmi.sendMessage(WifiMonitor.NETWORK_DISCONNECTION_EVENT, disconnectEventInfo);
        mLooper.dispatchAll();

        assertFalse(mWifiInfo.isEphemeral());
        assertNull(mWifiInfo.getRequestingPackageName());
    }

    @Test
    public void handleAssociationRejectionWhenRoaming() throws Exception {
        connect();

        assertTrue(SupplicantState.isConnecting(mWifiInfo.getSupplicantState()));

        when(mWifiNative.roamToNetwork(any(), any())).thenReturn(true);

        // Trigger roam to a BSSID.
        mCmi.startRoamToNetwork(FRAMEWORK_NETWORK_ID, sBSSID1);
        mLooper.dispatchAll();


        assertEquals(sBSSID1, mCmi.getConnectingBssid());
        assertEquals(FRAMEWORK_NETWORK_ID, mCmi.getConnectingWifiConfiguration().networkId);

        verify(mWifiNative).roamToNetwork(any(), any());
        assertEquals("RoamingState", getCurrentState().getName());

        // fail the connection.
        mCmi.sendMessage(WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(0, sWifiSsid, sBSSID, SupplicantState.DISCONNECTED));
        mLooper.dispatchAll();

        // Ensure we reset WifiInfo fields.
        assertFalse(SupplicantState.isConnecting(mWifiInfo.getSupplicantState()));
    }

    @Test
    public void testOemPaidNetworkCapability() throws Exception {
        mConnectedNetwork.oemPaid = true;
        connect();
        expectRegisterNetworkAgent((agentConfig) -> { },
                (cap) -> {
                    assertTrue(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID));
                });
    }
    @Test
    public void testNotOemPaidNetworkCapability() throws Exception {
        mConnectedNetwork.oemPaid = false;
        connect();
        expectRegisterNetworkAgent((agentConfig) -> { },
                (cap) -> {
                    assertFalse(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID));
                });
    }

    @Test
    public void testOemPrivateNetworkCapability() throws Exception {
        mConnectedNetwork.oemPrivate = true;
        connect();
        expectRegisterNetworkAgent((agentConfig) -> { },
                (cap) -> {
                    assertTrue(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE));
                });
    }

    @Test
    public void testNotOemPrivateNetworkCapability() throws Exception {
        mConnectedNetwork.oemPrivate = false;
        connect();
        expectRegisterNetworkAgent((agentConfig) -> { },
                (cap) -> {
                    assertFalse(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE));
                });
    }

    @Test
    public void testSendLinkProbeFailure() throws Exception {
        mCmi.probeLink(mLinkProbeCallback, -1);

        verify(mLinkProbeCallback).onFailure(LinkProbeCallback.LINK_PROBE_ERROR_NOT_CONNECTED);
        verify(mLinkProbeCallback, never()).onAck(anyInt());
        verify(mWifiNative, never()).probeLink(any(), any(), any(), anyInt());
    }

    @Test
    public void testSendLinkProbeSuccess() throws Exception {
        connect();

        mCmi.probeLink(mLinkProbeCallback, -1);

        verify(mWifiNative).probeLink(any(), any(), eq(mLinkProbeCallback), eq(-1));
        verify(mLinkProbeCallback, never()).onFailure(anyInt());
        verify(mLinkProbeCallback, never()).onAck(anyInt());
    }

    private void setupPasspointConnection() throws Exception {
        mConnectedNetwork = spy(WifiConfigurationTestUtil.createPasspointNetwork());
        mConnectedNetwork.carrierId = CARRIER_ID_1;
        doReturn(DATA_SUBID).when(mWifiCarrierInfoManager)
                .getBestMatchSubscriptionId(any(WifiConfiguration.class));
        when(mDataTelephonyManager.getSimOperator()).thenReturn("123456");
        when(mDataTelephonyManager.getSimState()).thenReturn(TelephonyManager.SIM_STATE_READY);
        mConnectedNetwork.enterpriseConfig.setAnonymousIdentity("");
        triggerConnect();

        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(mConnectedNetwork);
        when(mWifiConfigManager.getScanDetailCacheForNetwork(FRAMEWORK_NETWORK_ID))
                .thenReturn(mScanDetailCache);
        when(mScanRequestProxy.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());
        when(mScanDetailCache.getScanDetail(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq));
        when(mScanDetailCache.getScanResult(sBSSID)).thenReturn(
                getGoogleGuestScanDetail(TEST_RSSI, sBSSID, sFreq).getScanResult());

        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID);
        mLooper.dispatchAll();
        assertEquals("L3ProvisioningState", getCurrentState().getName());
    }

    /**
     * When connecting to a Passpoint network, verify that the Venue URL ANQP request is sent.
     */
    @Test
    public void testVenueUrlRequestForPasspointNetworks() throws Exception {
        setupPasspointConnection();
        verify(mPasspointManager).requestVenueUrlAnqpElement(any(ScanResult.class));
        assertEquals("L3ProvisioningState", getCurrentState().getName());
    }

    /**
     * Verify that the Venue URL ANQP request is not sent for non-Passpoint EAP networks
     */
    @Test
    public void testVenueUrlNotRequestedForNonPasspointNetworks() throws Exception {
        setupEapSimConnection();
        verify(mPasspointManager, never()).requestVenueUrlAnqpElement(any(ScanResult.class));
        assertEquals("L3ProvisioningState", getCurrentState().getName());
    }

    @Test
    public void testFirmwareRoam() throws Exception {
        connect();

        // Now send a network connection (indicating a roam) event
        mCmi.sendMessage(WifiMonitor.NETWORK_CONNECTION_EVENT, 0, 0, sBSSID1);
        mLooper.dispatchAll();

        verify(mContext, times(2)).sendStickyBroadcastAsUser(
                argThat(new NetworkStateChangedIntentMatcher(CONNECTED)), any());
    }

    @Test
    public void testProvisioningUpdateAfterConnect() throws Exception {
        connect();

        // Trigger a IP params update (maybe a dhcp lease renewal).
        DhcpResultsParcelable dhcpResults = new DhcpResultsParcelable();
        dhcpResults.baseConfiguration = new StaticIpConfiguration();
        dhcpResults.baseConfiguration.gateway = InetAddresses.parseNumericAddress("1.2.3.4");
        dhcpResults.baseConfiguration.ipAddress =
                new LinkAddress(InetAddresses.parseNumericAddress("192.168.1.100"), 0);
        dhcpResults.baseConfiguration.dnsServers.add(InetAddresses.parseNumericAddress("8.8.8.8"));
        dhcpResults.leaseDuration = 3600;

        injectDhcpSuccess(dhcpResults);
        mLooper.dispatchAll();

        verify(mContext, times(2)).sendStickyBroadcastAsUser(
                argThat(new NetworkStateChangedIntentMatcher(CONNECTED)), any());
    }

    /**
     * Verify that the Deauth-Imminent WNM-Notification is handled by relaying to the Passpoint
     * Manager.
     */
    @Test
    public void testHandlePasspointDeauthImminentWnmNotification() throws Exception {
        setupEapSimConnection();
        WnmData wnmData = WnmData.createDeauthImminentEvent(TEST_BSSID, "", false,
                TEST_DELAY_IN_SECONDS);
        mCmi.sendMessage(WifiMonitor.HS20_DEAUTH_IMMINENT_EVENT, 0, 0, wnmData);
        mLooper.dispatchAll();
        verify(mPasspointManager).handleDeauthImminentEvent(eq(wnmData),
                any(WifiConfiguration.class));
    }

    /**
     * Verify that the network selection status will be updated and the function onEapFailure()
     * in EapFailureNotifier is called when a EAP Authentication failure is detected
     * with carrier erroe code.
     */
    @Test
    public void testCarrierEapFailure() throws Exception {
        initializeAndAddNetworkAndVerifySuccess();

        startConnectSuccess();

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = sSSID;
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        config.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        when(mWifiConfigManager.getConfiguredNetwork(anyInt())).thenReturn(config);
        when(mEapFailureNotifier.onEapFailure(anyInt(), eq(config))).thenReturn(true);

        mCmi.sendMessage(WifiMonitor.AUTHENTICATION_FAILURE_EVENT,
                WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE,
                DEFINED_ERROR_CODE
        );
        mLooper.dispatchAll();

        verify(mEapFailureNotifier).onEapFailure(
                DEFINED_ERROR_CODE, config);
        verify(mWifiBlocklistMonitor).loadCarrierConfigsForDisableReasonInfos();
        verify(mWifiConfigManager).updateNetworkSelectionStatus(anyInt(),
                eq(WifiConfiguration.NetworkSelectionStatus
                        .DISABLED_AUTHENTICATION_FAILURE_CARRIER_SPECIFIC));
    }

    /**
     * When connected to a Passpoint network, verify that the Venue URL and T&C URL are updated in
     * the {@link LinkProperties} object when provisioning complete and when link properties change
     * events are received.
     */
    @Test
    public void testVenueAndTCUrlsUpdateForPasspointNetworks() throws Exception {
        setupPasspointConnection();
        DhcpResultsParcelable dhcpResults = new DhcpResultsParcelable();
        dhcpResults.baseConfiguration = new StaticIpConfiguration();
        dhcpResults.baseConfiguration.gateway = InetAddresses.parseNumericAddress("1.2.3.4");
        dhcpResults.baseConfiguration.ipAddress =
                new LinkAddress(InetAddresses.parseNumericAddress("192.168.1.100"), 0);
        dhcpResults.baseConfiguration.dnsServers.add(InetAddresses.parseNumericAddress("8.8.8.8"));
        dhcpResults.leaseDuration = 3600;
        injectDhcpSuccess(dhcpResults);

        mLooper.dispatchAll();
        mIpClientCallback.onLinkPropertiesChange(new LinkProperties());
        mLooper.dispatchAll();
        verify(mPasspointManager).clearTermsAndConditionsUrl();
        verify(mPasspointManager, times(2)).getVenueUrl(any(ScanResult.class));
        verify(mPasspointManager, times(2)).getTermsAndConditionsUrl();
    }

    /**
     * Verify that the T&C WNM-Notification is handled by relaying to the Passpoint
     * Manager.
     */
    @Test
    public void testHandlePasspointTermsAndConditionsWnmNotification() throws Exception {
        setupEapSimConnection();
        WnmData wnmData = WnmData.createTermsAndConditionsAccetanceRequiredEvent(TEST_BSSID,
                TEST_TERMS_AND_CONDITIONS_URL);
        when(mPasspointManager.handleTermsAndConditionsEvent(eq(wnmData),
                any(WifiConfiguration.class))).thenReturn(true);
        mCmi.sendMessage(WifiMonitor.HS20_TERMS_AND_CONDITIONS_ACCEPTANCE_REQUIRED_EVENT,
                0, 0, wnmData);
        mLooper.dispatchAll();
        verify(mPasspointManager).handleTermsAndConditionsEvent(eq(wnmData),
                any(WifiConfiguration.class));
        verify(mWifiNative, never()).disconnect(anyString());
    }

    /**
     * Verify that when a bad URL is received in the T&C WNM-Notification, the connection is
     * disconnected.
     */
    @Test
    public void testHandlePasspointTermsAndConditionsWnmNotificationWithBadUrl() throws Exception {
        setupEapSimConnection();
        WnmData wnmData = WnmData.createTermsAndConditionsAccetanceRequiredEvent(TEST_BSSID,
                TEST_TERMS_AND_CONDITIONS_URL);
        when(mPasspointManager.handleTermsAndConditionsEvent(eq(wnmData),
                any(WifiConfiguration.class))).thenReturn(false);
        mCmi.sendMessage(WifiMonitor.HS20_TERMS_AND_CONDITIONS_ACCEPTANCE_REQUIRED_EVENT,
                0, 0, wnmData);
        mLooper.dispatchAll();
        verify(mPasspointManager).handleTermsAndConditionsEvent(eq(wnmData),
                any(WifiConfiguration.class));
        verify(mWifiNative).disconnect(eq(WIFI_IFACE_NAME));
    }
}