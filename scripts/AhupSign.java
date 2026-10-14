import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Base64;

/** AHUP v3: exact manifest bytes, SHA256withRSA, binary signature.sig.
 * Credentials: five Base64 UTF-8 lines on stdin (keystore, store password,
 * alias, key password, store type). Exported trust pin is Base64 X.509 DER.
 */
public class AhupSign {
    public static void main(String[] args) {
        try {
            execute(args);
        } catch (Exception failure) {
            // Provider exception messages may contain credentials.
            System.err.println("AHUP signer failed; verify the signing configuration.");
            System.exit(1);
        }
    }

    private static void execute(String[] args) throws Exception {
        if (args.length >= 4 && "verify".equals(args[0]) && args.length % 2 == 0) {
            byte[] encoded = Files.readAllBytes(Path.of(args[1]));
            if (encoded.length == 0) throw new IllegalArgumentException();
            if (encoded[0] != 0x30) {
                String text = new String(encoded, StandardCharsets.US_ASCII)
                        .replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "")
                        .replaceAll("\\s", "");
                encoded = Base64.getDecoder().decode(text);
            }
            var certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(encoded));
            for (int i = 2; i < args.length; i += 2) {
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(certificate.getPublicKey());
                verifier.update(Files.readAllBytes(Path.of(args[i])));
                if (!verifier.verify(Files.readAllBytes(Path.of(args[i + 1])))) throw new IllegalArgumentException();
            }
            System.out.println("Verified " + (args.length - 2) / 2 + " manifest signatures");
            return;
        }
        if (args.length != 2 && args.length != 3) throw new IllegalArgumentException();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));
        String keystore = field(input);
        char[] storePassword = field(input).toCharArray();
        String alias = field(input);
        char[] keyPassword = field(input).toCharArray();
        String storeType = field(input);
        KeyStore keys = KeyStore.getInstance(storeType);
        try {
            try (InputStream source = Files.newInputStream(Path.of(keystore))) {
                keys.load(source, storePassword);
            }
            if ("export-certificate".equals(args[0]) && args.length == 2) {
                byte[] certificate = keys.getCertificate(alias).getEncoded();
                Files.writeString(Path.of(args[1]), Base64.getEncoder().encodeToString(certificate) + "\n",
                        StandardCharsets.US_ASCII);
            } else if ("sign".equals(args[0]) && args.length == 3) {
                PrivateKey key = (PrivateKey) keys.getKey(alias, keyPassword);
                if (!"RSA".equalsIgnoreCase(key.getAlgorithm())) throw new IllegalArgumentException();
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(key);
                signature.update(Files.readAllBytes(Path.of(args[1])));
                Files.write(Path.of(args[2]), signature.sign());
            } else {
                throw new IllegalArgumentException();
            }
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }

    private static String field(BufferedReader input) throws Exception {
        String line = input.readLine();
        if (line == null) throw new IllegalArgumentException();
        return new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
    }
}
