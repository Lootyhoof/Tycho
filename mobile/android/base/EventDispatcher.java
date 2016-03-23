/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import org.mozilla.gecko.GoannaAppShell;
import org.mozilla.gecko.GoannaEvent;
import org.mozilla.gecko.mozglue.RobocopTarget;
import org.mozilla.gecko.util.EventCallback;
import org.mozilla.gecko.util.GoannaEventListener;
import org.mozilla.gecko.util.NativeEventListener;
import org.mozilla.gecko.util.NativeJSContainer;
import org.mozilla.gecko.util.NativeJSObject;
import org.mozilla.gecko.util.ThreadUtils;

import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@RobocopTarget
public final class EventDispatcher {
    private static final String LOGTAG = "GoannaEventDispatcher";
    private static final String GUID = "__guid__";
    private static final String STATUS_ERROR = "error";
    private static final String STATUS_SUCCESS = "success";

    private static final EventDispatcher INSTANCE = new EventDispatcher();

    /**
     * The capacity of a HashMap is rounded up to the next power-of-2. Every time the size
     * of the map goes beyond 75% of the capacity, the map is rehashed. Therefore, to
     * empirically determine the initial capacity that avoids rehashing, we need to
     * determine the initial size, divide it by 75%, and round up to the next power-of-2.
     */
    private static final int GECKO_NATIVE_EVENTS_COUNT = 0; // Default for HashMap
    private static final int GECKO_JSON_EVENTS_COUNT = 256; // Empirically measured

    private final Map<String, List<NativeEventListener>> mGoannaThreadNativeListeners =
        new HashMap<String, List<NativeEventListener>>(GECKO_NATIVE_EVENTS_COUNT);
    private final Map<String, List<GoannaEventListener>> mGoannaThreadJSONListeners =
        new HashMap<String, List<GoannaEventListener>>(GECKO_JSON_EVENTS_COUNT);

    public static EventDispatcher getInstance() {
        return INSTANCE;
    }

    private EventDispatcher() {
    }

    private <T> void registerListener(final Class<? extends List<T>> listType,
                                      final Map<String, List<T>> listenersMap,
                                      final T listener,
                                      final String[] events) {
        try {
            synchronized (listenersMap) {
                for (final String event : events) {
                    List<T> listeners = listenersMap.get(event);
                    if (listeners == null) {
                        listeners = listType.newInstance();
                        listenersMap.put(event, listeners);
                    }
                    if (!AppConstants.RELEASE_BUILD && listeners.contains(listener)) {
                        throw new IllegalStateException("Already registered " + event);
                    }
                    listeners.add(listener);
                }
            }
        } catch (final IllegalAccessException | InstantiationException e) {
            throw new IllegalArgumentException("Invalid new list type", e);
        }
    }

    private <T> void checkNotRegistered(final Map<String, List<T>> listenersMap,
                                        final String[] events) {
        synchronized (listenersMap) {
            for (final String event: events) {
                if (listenersMap.get(event) != null) {
                    throw new IllegalStateException(
                        "Already registered " + event + " under a different type");
                }
            }
        }
    }

    private <T> void unregisterListener(final Map<String, List<T>> listenersMap,
                                        final T listener,
                                        final String[] events) {
        synchronized (listenersMap) {
            for (final String event : events) {
                List<T> listeners = listenersMap.get(event);
                if ((listeners == null ||
                     !listeners.remove(listener)) && !AppConstants.RELEASE_BUILD) {
                    throw new IllegalArgumentException(event + " was not registered");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void registerGoannaThreadListener(final NativeEventListener listener,
                                            final String... events) {
        checkNotRegistered(mGoannaThreadJSONListeners, events);

        // For listeners running on the Goanna thread, we want to notify the listeners
        // outside of our synchronized block, because the listeners may take an
        // indeterminate amount of time to run. Therefore, to ensure concurrency when
        // iterating the list outside of the synchronized block, we use a
        // CopyOnWriteArrayList.
        registerListener((Class)CopyOnWriteArrayList.class,
                         mGoannaThreadNativeListeners, listener, events);
    }

    @Deprecated // Use NativeEventListener instead
    @SuppressWarnings("unchecked")
    public void registerGoannaThreadListener(final GoannaEventListener listener,
                                            final String... events) {
        checkNotRegistered(mGoannaThreadNativeListeners, events);

        registerListener((Class)CopyOnWriteArrayList.class,
                         mGoannaThreadJSONListeners, listener, events);
    }

    public void unregisterGoannaThreadListener(final NativeEventListener listener,
                                              final String... events) {
        unregisterListener(mGoannaThreadNativeListeners, listener, events);
    }

    @Deprecated // Use NativeEventListener instead
    public void unregisterGoannaThreadListener(final GoannaEventListener listener,
                                              final String... events) {
        unregisterListener(mGoannaThreadJSONListeners, listener, events);
    }

    public void dispatchEvent(final NativeJSContainer message) {
        // First try native listeners.
        final String type = message.optString("type", null);
        if (type == null) {
            Log.e(LOGTAG, "JSON message must have a type property");
            return;
        }

        final List<NativeEventListener> listeners;
        synchronized (mGoannaThreadNativeListeners) {
            listeners = mGoannaThreadNativeListeners.get(type);
        }

        final String guid = message.optString(GUID, null);
        EventCallback callback = null;
        if (guid != null) {
            callback = new GoannaEventCallback(guid, type);
        }

        if (listeners != null) {
            if (listeners.size() == 0) {
                Log.w(LOGTAG, "No listeners for " + type);
            }
            try {
                for (final NativeEventListener listener : listeners) {
                    listener.handleMessage(type, message, callback);
                }
            } catch (final NativeJSObject.InvalidPropertyException e) {
                Log.e(LOGTAG, "Exception occurred while handling " + type, e);
            }
            // If we found native listeners, we assume we don't have any JSON listeners
            // and return early. This assumption is checked when registering listeners.
            return;
        }

        try {
            // If we didn't find native listeners, try JSON listeners.
            dispatchEvent(new JSONObject(message.toString()), callback);
        } catch (final JSONException e) {
            Log.e(LOGTAG, "Cannot parse JSON", e);
        } catch (final UnsupportedOperationException e) {
            Log.e(LOGTAG, "Cannot convert message to JSON", e);
        }
    }

    public void dispatchEvent(final JSONObject message, final EventCallback callback) {
        // {
        //   "type": "value",
        //   "event_specific": "value",
        //   ...
        try {
            final String type = message.getString("type");

            List<GoannaEventListener> listeners;
            synchronized (mGoannaThreadJSONListeners) {
                listeners = mGoannaThreadJSONListeners.get(type);
            }
            if (listeners == null || listeners.size() == 0) {
                Log.w(LOGTAG, "No listeners for " + type);

                // If there are no listeners, dispatch an error.
                if (callback != null) {
                    callback.sendError("No listeners for request");
                }
                return;
            }
            for (final GoannaEventListener listener : listeners) {
                listener.handleMessage(type, message);
            }
        } catch (final JSONException e) {
            Log.e(LOGTAG, "handleGoannaMessage throws " + e, e);
        }
    }

    @RobocopTarget
    @Deprecated
    public static void sendResponse(JSONObject message, Object response) {
        sendResponseHelper(STATUS_SUCCESS, message, response);
    }

    @Deprecated
    public static void sendError(JSONObject message, Object response) {
        sendResponseHelper(STATUS_ERROR, message, response);
    }

    @Deprecated
    private static void sendResponseHelper(String status, JSONObject message, Object response) {
        try {
            final String topic = message.getString("type") + ":Response";
            final JSONObject wrapper = new JSONObject();
            wrapper.put(GUID, message.getString(GUID));
            wrapper.put("status", status);
            wrapper.put("response", response);

            if (ThreadUtils.isOnGoannaThread()) {
                GoannaAppShell.notifyGoannaObservers(topic, wrapper.toString());
            } else {
                GoannaAppShell.sendEventToGoanna(
                    GoannaEvent.createBroadcastEvent(topic, wrapper.toString()));
            }
        } catch (final JSONException e) {
            Log.e(LOGTAG, "Unable to send response", e);
        }
    }

    private static class GoannaEventCallback implements EventCallback {
        private final String guid;
        private final String type;
        private boolean sent;

        public GoannaEventCallback(final String guid, final String type) {
            this.guid = guid;
            this.type = type;
        }

        @Override
        public void sendSuccess(final Object response) {
            sendResponse(STATUS_SUCCESS, response);
        }

        @Override
        public void sendError(final Object response) {
            sendResponse(STATUS_ERROR, response);
        }

        private void sendResponse(final String status, final Object response) {
            if (sent) {
                throw new IllegalStateException("Callback has already been executed for type=" +
                        type + ", guid=" + guid);
            }

            sent = true;

            try {
                final String topic = type + ":Response";
                final JSONObject wrapper = new JSONObject();
                wrapper.put(GUID, guid);
                wrapper.put("status", status);
                wrapper.put("response", response);

                if (ThreadUtils.isOnGoannaThread()) {
                    GoannaAppShell.notifyGoannaObservers(topic, wrapper.toString());
                } else {
                    GoannaAppShell.sendEventToGoanna(
                        GoannaEvent.createBroadcastEvent(topic, wrapper.toString()));
                }
            } catch (final JSONException e) {
                Log.e(LOGTAG, "Unable to send response for: " + type, e);
            }
        }
    }
}
