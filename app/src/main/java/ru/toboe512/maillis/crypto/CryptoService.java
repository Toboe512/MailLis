package ru.toboe512.maillis.crypto;

import java.io.IOException;
import java.security.GeneralSecurityException;

public interface CryptoService {


    String encrypt(String plainText, byte[] peerPublicKeyBytes) throws GeneralSecurityException, IOException;

    String decrypted(String base64Ciphertext) throws GeneralSecurityException, IOException;

    byte[] encryptBytes(byte[] rawImageBytes, byte[] peerPublicKeyBytes) throws GeneralSecurityException, IOException;

    byte[] decryptBytes(byte[] encryptedImageBytes) throws GeneralSecurityException;

    byte[] getMyPublicKeyBytes() throws IOException, GeneralSecurityException;

    String getMyPublicKeyBas64() throws IOException, GeneralSecurityException;

}
