package pt.up.fc.sahc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.Scanner;

public class SmartcardSigner implements SignatureInterface {

    private PrivateKey privateKey;
    private Certificate[] certificateChain;
    private Provider signingProvider;

    public SmartcardSigner(PrivateKey privateKey, Certificate[] certificateChain, Provider signingProvider) {
        this.privateKey = privateKey;
        this.certificateChain = certificateChain;
        this.signingProvider = signingProvider;
    }

    @Override
    public byte[] sign(InputStream content) throws IOException {
        try {
            byte[] contentBytes = content.readAllBytes();

            CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
            X509Certificate cert = (X509Certificate) certificateChain[0];

            ContentSigner contentSigner = new ContentSigner() {
                private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

                @Override
                public AlgorithmIdentifier getAlgorithmIdentifier() {
                    return new AlgorithmIdentifier(
                        PKCSObjectIdentifiers.sha256WithRSAEncryption,
                        DERNull.INSTANCE
                    );
                }

                @Override
                public OutputStream getOutputStream() {
                    return baos;
                }

                @Override
                public byte[] getSignature() {
                    try {
                        byte[] signedAttrs = baos.toByteArray();
                        Signature rsa = Signature.getInstance("SHA256withRSA");
                        rsa.initSign(privateKey);
                        rsa.update(signedAttrs);
                        return rsa.sign();
                    } catch (Exception e) {
                        throw new RuntimeException("Erro ao assinar", e);
                    }
                }
            };

            gen.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder()
                        .setProvider(new BouncyCastleProvider())
                        .build()
                ).build(contentSigner, cert)
            );
            gen.addCertificates(new JcaCertStore(Arrays.asList(certificateChain)));

            CMSProcessableByteArray msg = new CMSProcessableByteArray(contentBytes);
            CMSSignedData signedData = gen.generate(msg, false);

            return signedData.getEncoded();
        } catch (Exception e) {
            throw new IOException("Erro a gerar assinatura CMS", e);
        }
    }

    public static void main(String[] args) {
        String pasta = System.getProperty("user.home") + "/Uni/Mestrado/SAHC/work/pdf-signer/";
        String cfgPath = System.getProperty("pkcs11.cfg", pasta + "pkcs11.cfg");
        String inPdf  = pasta + "teste_limpo.pdf";
        String outPdf = pasta + "teste_limpo_assinado.pdf";

        try {
            Security.addProvider(new BouncyCastleProvider());

            Provider pkcs11Provider = Security.getProvider("SunPKCS11");
            pkcs11Provider = pkcs11Provider.configure(cfgPath);
            Security.addProvider(pkcs11Provider);

            System.out.print("Introduza o PIN do Smartcard: ");
            Scanner scanner = new Scanner(System.in);
            char[] pin = scanner.nextLine().toCharArray();

            KeyStore ks = KeyStore.getInstance("PKCS11", pkcs11Provider);
            ks.load(null, pin);
            System.out.println("Login no cartao efetuado com sucesso!");

            String alias = null;
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String current = aliases.nextElement();
                if (ks.isKeyEntry(current)) {
                    X509Certificate c = (X509Certificate) ks.getCertificate(current);
                    String cn = c.getSubjectX500Principal().getName();
                    System.out.println("Encontrado: " + current + " -> " + cn);
                    if (cn.contains("Sign") || alias == null) {
                        alias = current;
                    }
                }
            }

            if (alias == null) throw new Exception("Nenhuma chave encontrada!");

            PrivateKey pk = (PrivateKey) ks.getKey(alias, null);
            Certificate[] chain = ks.getCertificateChain(alias);

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate rootCA = cf.generateCertificate(
                new FileInputStream(System.getProperty("user.home") +
                    "/Uni/Mestrado/SAHC/work/rootCA.crt")
            );
            Certificate[] fullChain = new Certificate[]{ chain[0], rootCA };

            System.out.println("A usar: " +
                ((X509Certificate) fullChain[0]).getSubjectX500Principal().getName());

            PDDocument doc = PDDocument.load(new File(inPdf));
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName("SAHC Signing User");
            signature.setLocation("FCUP, Porto");
            signature.setReason("Prova de Conceito SAHC PKI");
            signature.setSignDate(Calendar.getInstance());

            SmartcardSigner signer = new SmartcardSigner(pk, fullChain, pkcs11Provider);
            doc.addSignature(signature, signer);

            FileOutputStream fos = new FileOutputStream(outPdf);
            doc.saveIncremental(fos);
            doc.close();
            fos.close();

            System.out.println("Sucesso! PDF assinado em: " + outPdf);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
