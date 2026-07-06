package com.opendashcam;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks GPS coordinates and resolves a human-readable address for video watermarks.
 */
public final class RecordingLocationTracker {
    private static final String TAG = "RecordingLocationTracker";
    private static final long MIN_UPDATE_MS = 1_000L;
    private static final float MIN_DISTANCE_M = 0f;
    private static final long MIN_GEOCODE_INTERVAL_MS = 10_000L;
    private static final long MAX_LOCATION_AGE_MS = 5 * 60_000L;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService locationExecutor = Executors.newSingleThreadExecutor();
    private final AtomicReference<Location> lastLocation = new AtomicReference<>();
    private final AtomicReference<String> cachedAddress = new AtomicReference<>("");

    private LocationManager locationManager;
    private long lastGeocodeAtMs;
    private boolean started;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            updateLocation(location);
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    public RecordingLocationTracker(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void start() {
        if (started) {
            refreshCurrentLocation();
            return;
        }
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted");
            return;
        }
        started = true;
        locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Log.w(TAG, "LocationManager unavailable");
            started = false;
            return;
        }

        Location lastKnown = getBestLastKnownLocation();
        if (lastKnown != null) {
            updateLocation(lastKnown);
        }

        try {
            requestUpdates(LocationManager.FUSED_PROVIDER);
            requestUpdates(LocationManager.GPS_PROVIDER);
            requestUpdates(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission missing", e);
            started = false;
            return;
        }

        refreshCurrentLocation();
    }

    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
    }

    public void release() {
        stop();
        locationExecutor.shutdownNow();
    }

    public boolean hasFix() {
        Location location = lastLocation.get();
        return location != null && isRecent(location);
    }

    public String getCoordinatesLine() {
        Location location = lastLocation.get();
        if (location == null) {
            return appContext.getString(R.string.watermark_gps_unavailable);
        }
        return String.format(
                Locale.US,
                "GPS: %.6f, %.6f",
                location.getLatitude(),
                location.getLongitude()
        );
    }

    public String getAddressLine() {
        String address = cachedAddress.get();
        return TextUtils.isEmpty(address) ? null : address;
    }

    private void requestUpdates(String provider) {
        if (locationManager == null || !isProviderAvailable(provider)) {
            return;
        }
        locationManager.requestLocationUpdates(
                provider,
                MIN_UPDATE_MS,
                MIN_DISTANCE_M,
                locationListener,
                Looper.getMainLooper()
        );
    }

    private void refreshCurrentLocation() {
        if (locationManager == null || !hasLocationPermission()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                locationManager.getCurrentLocation(
                        LocationManager.FUSED_PROVIDER,
                        new CancellationSignal(),
                        locationExecutor,
                        location -> {
                            if (location != null) {
                                mainHandler.post(() -> updateLocation(location));
                            }
                        }
                );
            } catch (SecurityException e) {
                Log.w(TAG, "Unable to request current location", e);
            }
        }
    }

    private void updateLocation(Location location) {
        if (location == null) {
            return;
        }
        Location previous = lastLocation.get();
        if (previous != null && location.getTime() + 1_000L < previous.getTime()) {
            return;
        }
        lastLocation.set(location);
        scheduleGeocode(location);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean isProviderAvailable(String provider) {
        if (LocationManager.FUSED_PROVIDER.equals(provider)) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        }
        return locationManager.isProviderEnabled(provider);
    }

    private Location getBestLastKnownLocation() {
        Location best = null;
        String[] providers = {
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
        };
        for (String provider : providers) {
            if (!isProviderAvailable(provider)) {
                continue;
            }
            try {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location == null) {
                    continue;
                }
                if (best == null || location.getTime() > best.getTime()) {
                    best = location;
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Unable to read last known location", e);
            }
        }
        return best;
    }

    private static boolean isRecent(Location location) {
        return System.currentTimeMillis() - location.getTime() <= MAX_LOCATION_AGE_MS;
    }

    private void scheduleGeocode(Location location) {
        long now = System.currentTimeMillis();
        if (now - lastGeocodeAtMs < MIN_GEOCODE_INTERVAL_MS) {
            return;
        }
        lastGeocodeAtMs = now;

        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder is not available on this device");
            return;
        }

        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        locationExecutor.execute(() -> resolveAddress(latitude, longitude));
    }

    private void resolveAddress(double latitude, double longitude) {
        Locale locale = Locale.getDefault();
        Geocoder geocoder = new Geocoder(appContext, locale);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(latitude, longitude, 1, new Geocoder.GeocodeListener() {
                    @Override
                    public void onGeocode(List<Address> addresses) {
                        storeResolvedAddress(addresses);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        Log.w(TAG, "Geocoding failed: " + errorMessage);
                    }
                });
                return;
            }

            List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
            storeResolvedAddress(addresses);
        } catch (IOException e) {
            Log.w(TAG, "Geocoding failed", e);
        }
    }

    private void storeResolvedAddress(List<Address> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return;
        }
        String formatted = formatAddress(addresses.get(0));
        if (!TextUtils.isEmpty(formatted)) {
            cachedAddress.set(formatted);
        }
    }

    private static String formatAddress(Address address) {
        String line = address.getAddressLine(0);
        if (!TextUtils.isEmpty(line)) {
            return line;
        }

        StringBuilder builder = new StringBuilder();
        appendPart(builder, address.getThoroughfare());
        appendPart(builder, address.getSubThoroughfare());
        appendPart(builder, address.getLocality());
        appendPart(builder, address.getAdminArea());
        return builder.toString().trim();
    }

    private static void appendPart(StringBuilder builder, String part) {
        if (TextUtils.isEmpty(part)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(", ");
        }
        builder.append(part);
    }
}
