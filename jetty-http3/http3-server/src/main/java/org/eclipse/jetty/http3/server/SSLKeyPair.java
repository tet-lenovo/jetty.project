package org.eclipse.jetty.http3.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.util.Base64;

public class SSLKeyPair
{
    private static final String BEGIN_KEY = "-----BEGIN PRIVATE KEY-----";
    private static final String END_KEY = "-----END PRIVATE KEY-----";
    private static final String BEGIN_CERT = "-----BEGIN CERTIFICATE-----";
    private static final String END_CERT = "-----END CERTIFICATE-----";
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");
    private static final int LINE_LENGTH = 64;

    private final Key key;
    private final Certificate cert;
    private final String alias;

    public SSLKeyPair(Key key, Certificate cert, String alias)
    {
        this.key = key;
        this.cert = cert;
        this.alias = alias;
    }

    public SSLKeyPair(File storeFile, String storeType, char[] storePassword, String alias, char[] keyPassword) throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, IOException, CertificateException
    {
        KeyStore keyStore = KeyStore.getInstance(storeType);
        try (FileInputStream fis = new FileInputStream(storeFile))
        {
            keyStore.load(fis, storePassword);
            this.alias = alias;
            this.key = keyStore.getKey(alias, keyPassword);
            this.cert = keyStore.getCertificate(alias);
        }
    }

    /**
     * @return [0] is the key file, [1] is the cert file.
     */
    public File[] export(File targetFolder) throws Exception
    {
        File[] files = new File[2];
        files[0] = new File(targetFolder, alias + ".key");
        files[1] = new File(targetFolder, alias + ".crt");

        try (FileWriter fw = new FileWriter(files[0], StandardCharsets.US_ASCII))
        {
            fw.write(toPem(key));
        }
        try (FileWriter fw = new FileWriter(files[1], StandardCharsets.US_ASCII))
        {
            fw.write(toPem(cert));
        }
        return files;
    }

    private static String toPem(Key key)
    {
        Base64.Encoder encoder = Base64.getMimeEncoder(LINE_LENGTH, LINE_SEPARATOR.getBytes());
        String encodedText = new String(encoder.encode(key.getEncoded()));
        return BEGIN_KEY + LINE_SEPARATOR + encodedText + LINE_SEPARATOR + END_KEY;
    }

    private static String toPem(Certificate certificate) throws CertificateEncodingException
    {
        Base64.Encoder encoder = Base64.getMimeEncoder(LINE_LENGTH, LINE_SEPARATOR.getBytes());
        String encodedText = new String(encoder.encode(certificate.getEncoded()));
        return BEGIN_CERT + LINE_SEPARATOR + encodedText + LINE_SEPARATOR + END_CERT;
    }
}
