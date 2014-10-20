package com.zello.sdk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import java.security.MessageDigest;

public class Sdk implements SafeHandlerEvents, ServiceConnection {

	private String _package = "";
	private Activity _activity;
	private SafeHandler<Sdk> _handler;
	private Events _events;
	private boolean _resumed;
	private String _activeTabAction = "com.zello.sdk." + Util.generateUuid();
	private Contact _selectedContact = new Contact();
	private MessageIn _messageIn = new MessageIn();
	private MessageOut _messageOut = new MessageOut();
	private Contacts _contacts;
	private AppState _appState = new AppState();
	private boolean _connected;
	private BroadcastReceiver _receiverPackage; // Broadcast receiver for package install broadcasts
	private BroadcastReceiver _receiverAppState; // Broadcast receiver for app state broadcasts
	private BroadcastReceiver _receiverMessageState; // Broadcast receiver for message state broadcasts
	private BroadcastReceiver _receiverContactSelected; // Broadcast receiver for selected contact broadcasts
	private BroadcastReceiver _receiverActiveTab; // Broadcast receiver for last selected contact list tab

	private static final int AWAKE_TIMER = 1;

	private static final String _pttActivityClass = "com.zello.sdk.Activity";
	private static Intent _serviceIntent;

	public Sdk() {
	}

	public void getSelectedContact(Contact contact) {
		_selectedContact.copyTo(contact);
	}

	public void setSelectedContact(Contact contact) {
		if (contact != null) {
			ContactType type = contact.getType();
			selectContact(type == ContactType.CHANNEL || type == ContactType.GROUP ? 1 : 0, contact.getName());
		} else {
			selectContact(0, null);
		}
	}

	public void setSelectedUserOrGateway(String name) {
		selectContact(0, name);
	}

	public void setSelectedChannelOrGroup(String name) {
		selectContact(1, name);
	}


	public void getMessageIn(MessageIn message) {
		_messageIn.copyTo(message);
	}

	public void getMessageOut(MessageOut message) {
		_messageOut.copyTo(message);
	}

	public void getAppState(AppState state) {
		_appState.copyTo(state);
	}

	@SuppressLint("InlinedApi")
	@SuppressWarnings("deprecation")
	public void onCreate(String packageName, Activity activity, Events events) {
		_package = Util.toLowerCaseLexicographically(Util.emptyIfNull(packageName));
		_activity = activity;
		_events = events;
		_handler = new SafeHandler<Sdk>(this);
		_appState._available = isAppAvailable();
		if (activity != null) {
			// Spin app the main app
//			Intent intent = new Intent(Intent.ACTION_VIEW, null);
//			intent.setClassName(packageName, "com.loudtalks.client.ui.AutoStartActivity");
//			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//			intent.putExtra("com.loudtalks.refresh", true);
//			try {
//				activity.startActivity(intent);
//			} catch (Throwable ignored) {
//			}
			connect();
			// Register to receive package install broadcasts
			_receiverPackage = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					updateAppAvailable();
					if (intent != null) {
						String action = intent.getAction();
						if (action != null) {
							if (action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE) || action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
								String[] pkgs = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
								if (pkgs != null) {
									for (String pkg : pkgs) {
										if (pkg.equalsIgnoreCase(_package)) {
											reconnect();
											updateSelectedContact(null);
											updateContacts();
											break;
										}
									}
								}
							} else {
								Uri data = intent.getData();
								if (data != null) {
									String pkg = data.getSchemeSpecificPart();
									if (pkg != null && pkg.equalsIgnoreCase(_package)) {
										reconnect();
										updateSelectedContact(null);
										updateContacts();
									}
								}
							}
						}
					}
				}
			};
			IntentFilter filterPackage = new IntentFilter();
			filterPackage.addAction(Intent.ACTION_PACKAGE_ADDED);
			//noinspection deprecation
			filterPackage.addAction(Intent.ACTION_PACKAGE_INSTALL);
			filterPackage.addAction(Intent.ACTION_PACKAGE_REMOVED);
			filterPackage.addAction(Intent.ACTION_PACKAGE_REPLACED);
			filterPackage.addAction(Intent.ACTION_PACKAGE_CHANGED);
			filterPackage.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
			filterPackage.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
			filterPackage.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
			filterPackage.addDataScheme("package");
			activity.registerReceiver(_receiverPackage, filterPackage);
			// Register to receive app state broadcasts
			_receiverAppState = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					updateAppState(intent);
				}
			};
			Intent intentStickyAppState = activity.registerReceiver(_receiverAppState, new IntentFilter(_package + "." + Constants.ACTION_APP_STATE));
			updateAppState(intentStickyAppState);
			updateContacts();
			// Register to receive message state broadcasts
			_receiverMessageState = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					updateMessageState(intent);
				}
			};
			Intent intentStickyMessageState = activity.registerReceiver(_receiverMessageState, new IntentFilter(_package + "." + Constants.ACTION_MESSAGE_STATE));
			updateMessageState(intentStickyMessageState);
			// Register to receive selected contact broadcasts
			_receiverContactSelected = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					updateSelectedContact(intent);
				}
			};
			Intent intentStickySelectedContact = activity.registerReceiver(_receiverContactSelected, new IntentFilter(_package + "." + Constants.ACTION_CONTACT_SELECTED));
			updateSelectedContact(intentStickySelectedContact);
			// Register to receive last selected contact list tab
			_receiverActiveTab = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					updateSelectedTab(intent);
				}
			};
			activity.registerReceiver(_receiverActiveTab, new IntentFilter(_activeTabAction));
		}
	}

	public void onDestroy() {
		disconnect();
		_resumed = false;
		Context activity = _activity;
		if (activity != null) {
			activity.unregisterReceiver(_receiverPackage);
			activity.unregisterReceiver(_receiverAppState);
			activity.unregisterReceiver(_receiverMessageState);
			activity.unregisterReceiver(_receiverContactSelected);
			activity.unregisterReceiver(_receiverActiveTab);
		}
		Contacts contacts = _contacts;
		if (contacts != null) {
			contacts.close();
		}
		_receiverPackage = null;
		_receiverAppState = null;
		_receiverMessageState = null;
		_receiverContactSelected = null;
		_receiverActiveTab = null;
		stopAwakeTimer();
		_handler = null;
		_activity = null;
		_events = null;
		_package = "";
		_contacts = null;
	}

	public void onResume() {
		if (!_resumed) {
			_resumed = true;
			sendStayAwake();
			startAwakeTimer();
		}
	}

	public void onPause() {
		_resumed = false;
		stopAwakeTimer();
	}

	public void selectContact(String title, Tab[] tabs, Tab activeTab, Theme theme) {
		Activity activity = _activity;
		if (activity != null) {
			String tabList = tabsToString(tabs);
			if (tabList != null) {
				try {
					Intent intent = new Intent();
					intent.setComponent(new ComponentName(_package, _pttActivityClass));
					intent.setAction(Intent.ACTION_PICK);
					intent.putExtra(Intent.EXTRA_TITLE, title); // Activity title; optional
					intent.putExtra(Constants.EXTRA_TABS, tabList); // Set of displayed tabs; required; any combination of RECENTS, USERS and CHANNELS
					intent.putExtra(Constants.EXTRA_TAB, tabToString(activeTab)); // Initially active tab; optional; can be RECENTS, USERS or CHANNELS
					intent.putExtra(Constants.EXTRA_CALLBACK, _activeTabAction); // Last selected tab callback action; optional
					if (theme == Theme.LIGHT) {
						intent.putExtra(Constants.EXTRA_THEME, Constants.VALUE_LIGHT);
					}
					activity.startActivityForResult(intent, 0);
				} catch (Exception ignored) {
					// ActivityNotFoundException
				}
			}
		}
	}

	public void beginMessage() {
		Activity activity = _activity;
		if (activity != null) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_BEGIN_MESSAGE);
			activity.sendBroadcast(intent);
		}
	}

	public void endMessage() {
		Activity activity = _activity;
		if (activity != null) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_END_MESSAGE);
			activity.sendBroadcast(intent);
		}
	}

	public void selectContact(int type, String name) {
		Activity activity = _activity;
		if (activity != null) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_SELECT_CONTACT);
			if (name != null && name.length() > 0) {
				intent.putExtra(Constants.EXTRA_CONTACT_NAME, name);
				intent.putExtra(Constants.EXTRA_CONTACT_TYPE, type);
			}
			activity.sendBroadcast(intent);
		}
	}

	public boolean signIn(String network, String username, String password) {
		Activity activity = _activity;
		if (activity != null) {
			if (network != null && network.length() > 0 && username != null && username.length() > 0 && password != null && password.length() > 0) {
				Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
				intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_SIGN_IN);
				intent.putExtra(Constants.EXTRA_NETWORK_URL, network);
				intent.putExtra(Constants.EXTRA_USERNAME, username);
				intent.putExtra(Constants.EXTRA_PASSWORD, md5(password));
				activity.sendBroadcast(intent);
				return true;
			}
		}
		return true;
	}

	public void signOut() {
		Activity activity = _activity;
		if (activity != null) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_SIGN_OUT);
			activity.sendBroadcast(intent);
		}
	}

	public void lock(String applicationName, String packageName) {
		Activity activity = _activity;
		if (activity != null && applicationName != null && applicationName.length() > 0) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_LOCK);
			intent.putExtra(Constants.EXTRA_APPLICATION, applicationName);
			intent.putExtra(Constants.EXTRA_PACKAGE, packageName);
			activity.sendBroadcast(intent);
		}
	}

	public void unlock() {
		Activity activity = _activity;
		if (activity != null) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_LOCK);
			activity.sendBroadcast(intent);
		}
	}

	public void setStatus(Status status) {
		Activity activity = _activity;
		if (activity != null) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_SET_STATUS);
			intent.putExtra(Constants.EXTRA_STATE_BUSY, status == Status.BUSY);
			intent.putExtra(Constants.EXTRA_STATE_SOLO, status == Status.SOLO);
			activity.sendBroadcast(intent);
		}
	}

	public void setStatusMessage(String message) {
		Activity activity = _activity;
		if (activity != null) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_SET_STATUS);
			intent.putExtra(Constants.EXTRA_STATE_STATUS_MESSAGE, Util.emptyIfNull(message));
			activity.sendBroadcast(intent);
		}
	}

	public void openMainScreen() {
		Activity activity = _activity;
		if (activity != null) {
			try {
				Intent LaunchIntent = activity.getPackageManager().getLaunchIntentForPackage(_package);
				activity.startActivity(LaunchIntent);
			} catch (Exception ignored) {
				// PackageManager.NameNotFoundException, ActivityNotFoundException
			}
		}
	}

	public Contacts getContacts() {
		return _contacts;
	}

	public void setAutoRun(boolean enable) {
		Activity activity = _activity;
		if (activity != null) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_SET_AUTO_RUN);
			intent.putExtra(Constants.EXTRA_STATE_AUTO_RUN, enable);
			activity.sendBroadcast(intent);
		}
	}


	private void sendStayAwake() {
		Activity activity = _activity;
		if (activity != null) {
			Intent intent = new Intent(_package + "." + Constants.ACTION_COMMAND);
			intent.putExtra(Constants.EXTRA_COMMAND, Constants.VALUE_STAY_AWAKE);
			activity.sendBroadcast(intent);
		}
	}

	@Override
	public void handleMessageFromSafeHandler(Message message) {
		if (message != null) {
			if (message.what == AWAKE_TIMER) {
				if (_resumed) {
					sendStayAwake();
					Handler h = _handler;
					if (h != null) {
						h.sendMessageDelayed(h.obtainMessage(AWAKE_TIMER), Constants.STAY_AWAKE_TIMEOUT);
					}
				}
			}
		}
	}

	private void connect() {
		if (!_connected) {
			Activity activity = _activity;
			if (activity != null) {
				try {
					_connected = activity.bindService(getServiceIntent(), this, Context.BIND_AUTO_CREATE);
				} catch (Throwable t) {
					Log.i("zello sdk", "Error in Sdk.connect: " + t.toString());
				}
			}
		}
	}

	private void disconnect() {
		if (_connected) {
			_connected = false;
			Activity activity = _activity;
			if (activity != null) {
				activity.unbindService(this);
			}
		}
	}

	private Intent getServiceIntent() {
		Intent intent = _serviceIntent;
		if (intent == null) {
			intent = new Intent();
			intent.setClassName(_package, "com.loudtalks.client.ui.Svc");
			_serviceIntent = intent;
		}
		return intent;
	}

	private void reconnect() {
		disconnect();
		connect();
		Contacts contacts = _contacts;
		if (contacts != null) {
			contacts.invalidate();
		}
	}

	@Override
	public void onServiceConnected(ComponentName name, IBinder service) {
		Activity activity = _activity;
		if (activity != null) {
			activity.startService(getServiceIntent());
		}
	}

	@Override
	public void onServiceDisconnected(ComponentName name) {
		_connected = false;
	}

	private void startAwakeTimer() {
		if (_resumed) {
			Handler h = _handler;
			if (h != null) {
				h.sendMessageDelayed(h.obtainMessage(AWAKE_TIMER), Constants.STAY_AWAKE_TIMEOUT);
			}
		}
	}

	private void stopAwakeTimer() {
		Handler h = _handler;
		if (h != null) {
			h.removeMessages(AWAKE_TIMER);
		}
	}

	private void updateAppAvailable() {
		boolean available = isAppAvailable();
		if (available != _appState._available) {
			_appState._available = available;
			Events events = _events;
			if (events != null) {
				events.onAppStateChanged();
			}
		}
	}

	private void updateAppState(Intent intent) {
		_appState.reset();
		if (intent != null) {
			_appState._customBuild = intent.getBooleanExtra(Constants.EXTRA_STATE_CUSTOM_BUILD, false);
			_appState._configuring = intent.getBooleanExtra(Constants.EXTRA_STATE_CONFIGURING, false);
			_appState._locked = intent.getBooleanExtra(Constants.EXTRA_STATE_LOCKED, false);
			_appState._signedIn = intent.getBooleanExtra(Constants.EXTRA_STATE_SIGNED_IN, false);
			_appState._signingIn = intent.getBooleanExtra(Constants.EXTRA_STATE_SIGNING_IN, false);
			_appState._signingOut = intent.getBooleanExtra(Constants.EXTRA_STATE_SIGNING_OUT, false);
			_appState._reconnectTimer = intent.getIntExtra(Constants.EXTRA_STATE_RECONNECT_TIMER, -1);
			_appState._waitingForNetwork = intent.getBooleanExtra(Constants.EXTRA_STATE_WAITING_FOR_NETWORK, false);
			_appState._showContacts = intent.getBooleanExtra(Constants.EXTRA_STATE_SHOW_CONTACTS, false);
			_appState._busy = intent.getBooleanExtra(Constants.EXTRA_STATE_BUSY, false);
			_appState._solo = intent.getBooleanExtra(Constants.EXTRA_STATE_SOLO, false);
			_appState._autoRun = intent.getBooleanExtra(Constants.EXTRA_STATE_AUTO_RUN, false);
			_appState._statusMessage = intent.getStringExtra(Constants.EXTRA_STATE_STATUS_MESSAGE);
			_appState._network = intent.getStringExtra(Constants.EXTRA_STATE_NETWORK);
			_appState._networkUrl = intent.getStringExtra(Constants.EXTRA_STATE_NETWORK_URL);
			_appState._username = intent.getStringExtra(Constants.EXTRA_STATE_USERNAME);
		}
		Contacts contacts = _contacts;
		if (contacts != null) {
			contacts.invalidate();
		}
		Events events = _events;
		if (events != null) {
			events.onAppStateChanged();
		}
	}

	private void updateMessageState(Intent intent) {
		boolean out = false;
		boolean in = false;
		if (intent != null) {
			out = intent.getBooleanExtra(Constants.EXTRA_MESSAGE_OUT, false);
			in = !out && intent.getBooleanExtra(Constants.EXTRA_MESSAGE_IN, false);
			if (out) {
				_messageOut._to._name = intent.getStringExtra(Constants.EXTRA_CONTACT_NAME);
				_messageOut._to._fullName = intent.getStringExtra(Constants.EXTRA_CONTACT_FULL_NAME);
				_messageOut._to._displayName = intent.getStringExtra(Constants.EXTRA_CONTACT_DISPLAY_NAME);
				_messageOut._to._type = intToContactType(intent.getIntExtra(Constants.EXTRA_CONTACT_TYPE, -1));
				_messageOut._to._status = intToContactStatus(intent.getIntExtra(Constants.EXTRA_CONTACT_STATUS, 0));
				_messageOut._to._statusMessage = intent.getStringExtra(Constants.EXTRA_CONTACT_STATUS_MESSAGE);
				_messageOut._to._usersCount = intent.getIntExtra(Constants.EXTRA_CHANNEL_USERS_COUNT, 0);
				_messageOut._to._usersTotal = intent.getIntExtra(Constants.EXTRA_CHANNEL_USERS_TOTAL, 0);
				_messageOut._active = true;
				_messageOut._connecting = intent.getBooleanExtra(Constants.EXTRA_MESSAGE_CONNECTING, false);
			}
			if (in) {
				_messageIn._from._name = intent.getStringExtra(Constants.EXTRA_CONTACT_NAME);
				_messageIn._from._fullName = intent.getStringExtra(Constants.EXTRA_CONTACT_FULL_NAME);
				_messageIn._from._displayName = intent.getStringExtra(Constants.EXTRA_CONTACT_DISPLAY_NAME);
				_messageIn._from._type = intToContactType(intent.getIntExtra(Constants.EXTRA_CONTACT_TYPE, -1));
				_messageIn._from._status = intToContactStatus(intent.getIntExtra(Constants.EXTRA_CONTACT_STATUS, 0));
				_messageIn._from._statusMessage = intent.getStringExtra(Constants.EXTRA_CONTACT_STATUS_MESSAGE);
				_messageIn._from._usersCount = intent.getIntExtra(Constants.EXTRA_CHANNEL_USERS_COUNT, 0);
				_messageIn._from._usersTotal = intent.getIntExtra(Constants.EXTRA_CHANNEL_USERS_TOTAL, 0);
				_messageIn._author._name = intent.getStringExtra(Constants.EXTRA_CHANNEL_AUTHOR_NAME);
				_messageIn._author._fullName = intent.getStringExtra(Constants.EXTRA_CHANNEL_AUTHOR_FULL_NAME);
				_messageIn._author._displayName = intent.getStringExtra(Constants.EXTRA_CHANNEL_AUTHOR_DISPLAY_NAME);
				_messageIn._author._status = intToContactStatus(intent.getIntExtra(Constants.EXTRA_CHANNEL_AUTHOR_STATUS, 0));
				_messageIn._author._statusMessage = intent.getStringExtra(Constants.EXTRA_CHANNEL_AUTHOR_STATUS_MESSAGE);
				_messageIn._active = true;
			}
		}
		if (!in) {
			_messageIn.reset();
		}
		if (!out) {
			_messageOut.reset();
		}
		Events events = _events;
		if (events != null) {
			events.onMessageStateChanged();
		}
	}

	private void updateContacts() {
		Contacts contacts = _contacts;
		_contacts = null;
		if (contacts != null) {
			contacts.close();
		}
		Activity activity = _activity;
		if (activity != null) {
			_contacts = new Contacts(_package, activity, _handler, _events);
		}
	}

	private void updateSelectedContact(Intent intent) {
		String name = intent != null ? intent.getStringExtra(Constants.EXTRA_CONTACT_NAME) : null; // Contact name
		boolean selected = name != null && name.length() > 0;
		if (selected) {
			// Update info
			_selectedContact._name = name;
			_selectedContact._fullName = intent.getStringExtra(Constants.EXTRA_CONTACT_FULL_NAME);
			_selectedContact._displayName = intent.getStringExtra(Constants.EXTRA_CONTACT_DISPLAY_NAME);
			_selectedContact._type = intToContactType(intent.getIntExtra(Constants.EXTRA_CONTACT_TYPE, -1));
			_selectedContact._status = intToContactStatus(intent.getIntExtra(Constants.EXTRA_CONTACT_STATUS, 0));
			_selectedContact._statusMessage = intent.getStringExtra(Constants.EXTRA_CONTACT_STATUS_MESSAGE);
			_selectedContact._usersCount = intent.getIntExtra(Constants.EXTRA_CHANNEL_USERS_COUNT, 0);
			_selectedContact._usersTotal = intent.getIntExtra(Constants.EXTRA_CHANNEL_USERS_TOTAL, 0);
		} else {
			_selectedContact.reset();
		}
		Events events = _events;
		if (events != null) {
			events.onSelectedContactChanged();
		}
	}

	private void updateSelectedTab(Intent intent) {
		if (intent != null) {
			Tab tab = stringToTab(intent.getStringExtra(Constants.EXTRA_TAB));
			Events events = _events;
			if (events != null) {
				events.onLastContactsTabChanged(tab);
			}
		}
	}

	private boolean isAppAvailable() {
		Activity activity = _activity;
		if (activity != null) {
			try {
				return null != activity.getPackageManager().getLaunchIntentForPackage(_package);
			} catch (Exception e) {
				// PackageManager.NameNotFoundException
			}
		}
		return false;
	}

	static ContactType intToContactType(int type) {
		switch (type) {
			case 1:
				return ContactType.CHANNEL;
			case 3:
				return ContactType.GROUP;
			case 2:
				return ContactType.GATEWAY;
			default:
				return ContactType.USER;
		}
	}

	static ContactStatus intToContactStatus(int status) {
		switch (status) {
			case 1:
				return ContactStatus.STANDBY;
			case 2:
			case 4:
			case 5:
				return ContactStatus.AVAILABLE;
			case 3:
				return ContactStatus.BUSY;
			case 6:
				return ContactStatus.CONNECTING;
			default:
				return ContactStatus.OFFLINE;
		}
	}

	static String tabToString(Tab tab) {
		switch (tab) {
			case RECENTS:
				return Constants.VALUE_RECENTS;
			case USERS:
				return Constants.VALUE_USERS;
			case CHANNELS:
				return Constants.VALUE_CHANNELS;
		}
		return null;
	}

	static String tabsToString(Tab[] tabs) {
		String s = null;
		if (tabs != null) {
			for (Tab tab : tabs) {
				String name = tabToString(tab);
				if (name != null) {
					if (s == null) {
						s = name;
					} else {
						s += "," + name;
					}
				}
			}
		}
		return s;
	}

	static Tab stringToTab(String s) {
		if (s.equals(Constants.VALUE_USERS)) {
			return Tab.USERS;
		}
		if (s.equals(Constants.VALUE_CHANNELS)) {
			return Tab.CHANNELS;
		}
		return Tab.RECENTS;
	}

	static String bytesToHex(byte[] data) {
		if (data != null) {
			StringBuffer buf = new StringBuffer();
			for (int i = 0; i < data.length; i++) {
				int halfbyte = (data[i] >>> 4) & 0x0F;
				int two_halfs = 0;
				do {
					if ((0 <= halfbyte) && (halfbyte <= 9))
						buf.append((char) ('0' + halfbyte));
					else
						buf.append((char) ('a' + (halfbyte - 10)));
					halfbyte = data[i] & 0x0F;
				} while (two_halfs++ < 1);
			}
			return buf.toString();
		}
		return null;
	}

	static String md5(String s) {
		if (s != null && s.length() > 0) {
			try {
				MessageDigest digester = MessageDigest.getInstance("MD5");
				byte[] bytes = s.getBytes("UTF-8");
				digester.update(bytes, 0, bytes.length);
				byte[] digest = digester.digest();
				String hex = bytesToHex(digest);
				if (hex != null) {
					return hex;
				}
			} catch (Throwable t) {
				Log.i("zello sdk", "Error in Sdk.md5: " + t.toString());
			}
		}
		return "";
	}
}