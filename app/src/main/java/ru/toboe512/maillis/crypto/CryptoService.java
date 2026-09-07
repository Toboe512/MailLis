package ru.toboe512.maillis.crypto;

import android.content.Context;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;

public interface CryptoService {

    String decode(byte[] ciphertext, String chatId) throws GeneralSecurityException, UnsupportedEncodingException;

    byte[] encode(String txt, String chatId, byte[] publicKey) throws GeneralSecurityException, IOException;

    byte[] getPublicKeyToBytes() throws IOException;

    byte[] getPrivateKeyToBytes() throws IOException;
}
