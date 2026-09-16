package ru.toboe512.maillis.crypto;

import android.content.Context;
import android.util.Base64;

import com.google.crypto.tink.BinaryKeysetReader;
import com.google.crypto.tink.CleartextKeysetHandle;
import com.google.crypto.tink.HybridEncrypt;
import com.google.crypto.tink.KeysetHandle;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;

public interface CryptoService {


    String encode(String plainText, byte[] peerPublicKeyBytes) throws GeneralSecurityException, IOException;
    String decode(String base64Ciphertext) throws GeneralSecurityException, IOException;
    byte[] getMyPublicKeyBytes() throws IOException, GeneralSecurityException;
    String getMyPublicKeyBas64() throws IOException, GeneralSecurityException;
}
