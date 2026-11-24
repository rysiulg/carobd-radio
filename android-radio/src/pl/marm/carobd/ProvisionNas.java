package pl.marm.carobd;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.spec.PSource;
import org.json.JSONObject;

/** Przypisanie NAS dostępne dla autoryzowanego ADB przez uprawnienie DUMP w manifeście. */
public final class ProvisionNas extends BroadcastReceiver {
    private static final String ALIAS="carobd-enrollment";
    @Override public void onReceive(Context context,Intent intent) {
        PendingResult result=goAsync();
        new Thread(() -> {
            try {
                KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
                String action=intent.getAction();
                Settings settings=new Settings(context);
                if ("pl.marm.carobd.NAS_STATUS".equals(action)) {
                    result.setResultData(settings.hasNasCredential() ? "NAS_CONFIGURED" : "NAS_NOT_CONFIGURED");
                } else if ("pl.marm.carobd.NAS_REQUEST".equals(action)) {
                    KeyPairGenerator rsa=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
                    rsa.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build());
                    result.setResultData("KEY="+Base64.encodeToString(rsa.generateKeyPair().getPublic().getEncoded(),Base64.NO_WRAP));
                } else if ("pl.marm.carobd.NAS_INSTALL".equals(action)) {
                    String encrypted=intent.getStringExtra("payload");
                    if (encrypted==null || encrypted.length()>400 || !store.containsAlias(ALIAS)) throw new IllegalArgumentException();
                    Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");
                    cipher.init(Cipher.DECRYPT_MODE,(PrivateKey)store.getKey(ALIAS,null),new OAEPParameterSpec("SHA-256","MGF1",
                        MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
                    JSONObject value=new JSONObject(new String(cipher.doFinal(Base64.decode(encrypted,Base64.DEFAULT)),StandardCharsets.UTF_8));
                    String device=value.getString("device"), token=value.getString("token");
                    if (!Settings.identifier(device) || !token.matches("[A-Za-z0-9._~-]{24,80}")) throw new IllegalArgumentException();
                    settings.saveCredential(device,token);
                    store.deleteEntry(ALIAS); Maintenance.schedule(context);
                    result.setResultData("NAS_CONFIGURED");
                } else throw new IllegalArgumentException();
                result.setResultCode(Activity.RESULT_OK);
            } catch (Exception error) {
                result.setResultCode(Activity.RESULT_CANCELED);
                result.setResultData("NAS_ENROLLMENT_FAILED");
            } finally { result.finish(); }
        },"CarOBD enrollment").start();
    }
}
