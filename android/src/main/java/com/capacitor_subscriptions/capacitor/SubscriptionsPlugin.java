package com.capacitor_subscriptions.capacitor;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

@CapacitorPlugin(name = "Subscriptions")
public class SubscriptionsPlugin extends Plugin {

    private Subscriptions implementation;
    private BillingClient billingClient;
    private Boolean acknowledgePurchases = true;

    /**
     * Neu: Wir merken uns hier den aufrufenden purchaseProduct-Call,
     * um ihn nach dem Kaufabschluss aufzulösen.
     */
    private PluginCall pendingPurchaseCall = null;

    public SubscriptionsPlugin () {}

    // This listener is fired upon completing the billing flow ...
    private final PurchasesUpdatedListener purchasesUpdatedListener = (billingResult, purchases) -> {
        JSObject response = new JSObject();

        // Falls kein Kauf-Call aussteht, evtl. Abbruch
        if (pendingPurchaseCall == null) {
            // Wir können optional nach wie vor die NotifyListeners-Methode beibehalten,
            // falls du weiterhin das Event basierte Pattern brauchst.
            response.put("successful", false);
            response.put("message", "No pending purchase call to resolve");
            notifyListeners("ANDROID-PURCHASE-RESPONSE", response);
            return;
        }

        // Hier werten wir das Billing-Ergebnis aus
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            // Kauf erfolgreich
            // (Falls es mehrere Purchases gibt, du aber immer nur 1 erwartest, nimm [0].)
            Purchase currentPurchase = purchases.get(0);

            if (this.acknowledgePurchases && !currentPurchase.isAcknowledged() && currentPurchase.getPurchaseState() != Purchase.PurchaseState.PENDING) {
                // Acknowledge, damit Google das Abo nicht storniert
                AcknowledgePurchaseParams ackParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(currentPurchase.getPurchaseToken())
                        .build();

                billingClient.acknowledgePurchase(ackParams, billingResult1 -> {
                    // Kauf erfolgreich acknowledged
                    Log.i("Purchase ack", currentPurchase.getOriginalJson());

                    // Jetzt können wir die finalen Daten zurückgeben
                    JSObject data = buildPurchaseResult(currentPurchase, true, "Purchase successful & acknowledged");
                    pendingPurchaseCall.resolve(data);
                    pendingPurchaseCall = null;
                });
            } else {
                // Entweder already acknowledged oder wir acknowledge nicht
                JSObject data = buildPurchaseResult(currentPurchase, true, "Purchase successful (no ack needed)");
                pendingPurchaseCall.resolve(data);
                pendingPurchaseCall = null;
            }

        } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            // User hat Kauf abgebrochen
            JSObject data = new JSObject();
            data.put("successful", false);
            data.put("message", "Purchase canceled by user");
            pendingPurchaseCall.resolve(data);
            pendingPurchaseCall = null;
        } else {
            // Irgendwas anderes lief schief
            JSObject data = new JSObject();
            data.put("successful", false);
            data.put("message", "Purchase failed with code: " + billingResult.getResponseCode());
            pendingPurchaseCall.resolve(data);
            pendingPurchaseCall = null;
        }
    };

    /**
     * Hilfsfunktion, um ein JSON mit den wichtigsten Daten zu bauen.
     */
    private JSObject buildPurchaseResult(Purchase purchase, boolean success, String message) {
        JSObject data = new JSObject();
        data.put("successful", success);
        data.put("message", message);

        // Wichtige Felder extrahieren
        data.put("purchaseToken", purchase.getPurchaseToken());
        data.put("orderId", purchase.getOrderId());
        data.put("packageName", purchase.getPackageName());
        data.put("signature", purchase.getSignature());

        long purchaseTime = purchase.getPurchaseTime();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(purchaseTime);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        data.put("purchaseDate", sdf.format(calendar.getTime()));

        // Falls mehrere Produkte in dem Kauf stecken:
        List<String> productIds = purchase.getProducts();
        if (productIds != null && !productIds.isEmpty()) {
            data.put("productIds", productIds);
        }

        // Original JSON, falls du debuggen willst
        data.put("originalJson", purchase.getOriginalJson());

        return data;
    }

    @Override
    public void load() {
        this.billingClient = BillingClient.newBuilder(getContext())
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases()
                .build();

        implementation = new Subscriptions(this, billingClient);
    }

    @PluginMethod
    public void setApiVerificationDetails(PluginCall call) {
        String apiEndpoint = call.getString("apiEndpoint");
        String jwt = call.getString("jwt");
        String bid = call.getString("bid");

        if(apiEndpoint != null && jwt != null && bid != null) {
            implementation.setApiVerificationDetails(apiEndpoint, jwt, bid);
            call.resolve();
        } else {
            call.reject("Missing required parameters");
        }
    }

    @PluginMethod
    public void echo(PluginCall call) {
        String value = call.getString("value");
        JSObject ret = new JSObject();
        ret.put("value", implementation.echo(value));
        call.resolve(ret);
    }

    @PluginMethod
    public void getProductDetails(PluginCall call) {
        String productIdentifier = call.getString("productIdentifier");
        if (productIdentifier == null) {
            call.reject("Must provide a productID");
            return;
        }
        implementation.getProductDetails(productIdentifier, call);
    }

    @PluginMethod
    public void purchaseProduct(PluginCall call) {
        String productIdentifier = call.getString("productIdentifier");
        String accountId = call.getString("accountId");
        this.acknowledgePurchases = call.getBoolean("acknowledgePurchases", true);

        if(productIdentifier == null) {
            call.reject("Must provide a productID");
            return;
        }

        // Hier merken wir uns den Call
        this.pendingPurchaseCall = call;

        // Rufe die Implementation auf, die lediglich die BillingFlow startet
        // Das eigentliche Resolve findet erst in purchasesUpdatedListener statt.
        implementation.purchaseProduct(productIdentifier, accountId, call);
    }

    @PluginMethod
    public void getLatestTransaction(PluginCall call) {
        String productIdentifier = call.getString("productIdentifier");
        if(productIdentifier == null) {
            call.reject("Must provide a productID");
            return;
        }
        implementation.getLatestTransaction(productIdentifier, call);
    }

    @PluginMethod
    public void getCurrentEntitlements(PluginCall call) {
        implementation.getCurrentEntitlements(call);
    }

    @PluginMethod
    public void manageSubscriptions(PluginCall call) {
        String productIdentifier = call.getString("productIdentifier");
        String jwt = call.getString("jwt");

        if(productIdentifier == null) {
            call.reject("Must provide a productID");
            return;
        }

        if(jwt == null) {
            call.reject("Must provide a bundleID");
            return;
        }

        Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/account/subscriptions?sku=" + productIdentifier + "&package=" + jwt));
        getActivity().startActivity(browserIntent);
    }
}