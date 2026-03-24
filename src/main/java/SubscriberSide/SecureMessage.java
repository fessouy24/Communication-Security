package SubscriberSide;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.security.PrivateKey;
import java.util.function.BiConsumer;

public class SecureMessage extends UnicastRemoteObject implements SecureMessageI {
    private final PrivateKey privateKey;
    private final BiConsumer<String, String> messageConsumer;

    public SecureMessage(PrivateKey privateKey, BiConsumer<String, String> messageConsumer) throws RemoteException {
        super();
        this.privateKey = privateKey;
        this.messageConsumer = messageConsumer;
    }

    @Override
    public void receiveMessage(String sender, byte[] wrappedPayload) throws RemoteException {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(wrappedPayload);
            DataInputStream dis = new DataInputStream(bais);
            String actualSender = dis.readUTF();
            int payloadLen = dis.readInt();
            byte[] payload = new byte[payloadLen];
            dis.readFully(payload);

            ByteArrayInputStream payloadStream = new ByteArrayInputStream(payload);
            DataInputStream payloadDis = new DataInputStream(payloadStream);
            int nonceLen = payloadDis.readInt();
            byte[] nonce = new byte[nonceLen];
            payloadDis.readFully(nonce);
            int encAesKeyLen = payloadDis.readInt();
            byte[] encryptedAesKey = new byte[encAesKeyLen];
            payloadDis.readFully(encryptedAesKey);
            int encMsgLen = payloadDis.readInt();
            byte[] encryptedMessage = new byte[encMsgLen];
            payloadDis.readFully(encryptedMessage);

            // Upgraded padding scheme
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
            aesCipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
            byte[] plaintextBytes = aesCipher.doFinal(encryptedMessage);
            String plaintext = new String(plaintextBytes, java.nio.charset.StandardCharsets.UTF_8);

            messageConsumer.accept(actualSender, plaintext);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}