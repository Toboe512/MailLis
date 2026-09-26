package ru.toboe512.maillis.crypto;

import java.io.IOException;
import java.security.GeneralSecurityException;

public interface CryptoService {


    String encode(String plainText, byte[] peerPublicKeyBytes) throws GeneralSecurityException, IOException;

    String decode(String base64Ciphertext) throws GeneralSecurityException, IOException;

    byte[] getMyPublicKeyBytes() throws IOException, GeneralSecurityException;

    String getMyPublicKeyBas64() throws IOException, GeneralSecurityException;
}
