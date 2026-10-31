package com.pingche.web.desktopupdate;

import com.pingche.web.auth.DomainException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Verifies the same Minisign file and version-bound trusted comment used by Tauri 2. */
@Component
public class DesktopUpdateSignature {
    private final String publicKey;
    public DesktopUpdateSignature(@Value("${app.desktop-update-public-key:}") String configured) {
        String key=configured;
        if(key.isBlank()) {
            try(var input=new ClassPathResource("desktop-update-public.key").getInputStream()) {key=new String(input.readAllBytes(),StandardCharsets.UTF_8).trim();}
            catch(Exception ignored) {key="";}
        }
        this.publicKey=key.trim();
    }
    public String publicKey() { return publicKey; }
    public void verify(Path file,String encodedSignature,String version) {
        try {
            if(encodedSignature==null || encodedSignature.length()>32768) throw new IllegalArgumentException();
            String[] keyLines=decodeText(publicKey).strip().split("\\R");
            byte[] key=Base64.getDecoder().decode(keyLines[keyLines.length-1].trim());
            String[] lines=decodeText(encodedSignature.strip()).strip().split("\\R");
            if(key.length!=42 || lines.length!=4 || !lines[2].startsWith("trusted comment: ")) throw new IllegalArgumentException();
            byte[] sig=Base64.getDecoder().decode(lines[1]);
            byte[] global=Base64.getDecoder().decode(lines[3]);
            if(sig.length!=74 || global.length!=64 || sig[0]!='E' || sig[1]!='D'
                    || !MessageDigest.isEqual(Arrays.copyOfRange(key,2,10),Arrays.copyOfRange(sig,2,10))) throw new IllegalArgumentException();
            byte[] rawKey=Arrays.copyOfRange(key,10,42);
            byte[] prefix=HexFormat.of().parseHex("302a300506032b6570032100");
            byte[] x509=Arrays.copyOf(prefix,prefix.length+rawKey.length);System.arraycopy(rawKey,0,x509,prefix.length,rawKey.length);
            PublicKey edKey=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509));
            var digest=new Blake2bDigest(512);
            try(InputStream input=Files.newInputStream(file)) {
                byte[] buffer=new byte[65536];int read;
                while((read=input.read(buffer))!=-1) digest.update(buffer,0,read);
            }
            byte[] hash=new byte[64];digest.doFinal(hash,0);
            byte[] signature=Arrays.copyOfRange(sig,10,74);
            if(!verifyEd(edKey,hash,signature)) throw new IllegalArgumentException();
            String trusted=lines[2].substring(17);
            byte[] comment=trusted.getBytes(StandardCharsets.UTF_8);
            byte[] message=Arrays.copyOf(signature,signature.length+comment.length);
            System.arraycopy(comment,0,message,signature.length,comment.length);
            if(!verifyEd(edKey,message,global)) throw new IllegalArgumentException();
            String signedVersion=Arrays.stream(trusted.split("\\t")).filter(field->field.startsWith("version:")).map(field->field.substring(8)).findFirst().orElseThrow();
            if(!DesktopVersion.parse(version).equals(DesktopVersion.parse(signedVersion))) throw new IllegalArgumentException();
        } catch(Exception failure) {
            throw new DomainException("DESKTOP_SIGNATURE_INVALID","安装包签名、公钥或签名中的版本号不匹配；请上传本项目打包生成的安装包及对应 .sig 文件。",HttpStatus.BAD_REQUEST);
        }
    }
    private String decodeText(String value) {return new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);}
    private boolean verifyEd(PublicKey key,byte[] data,byte[] signature) throws GeneralSecurityException {
        var verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(data);return verifier.verify(signature);
    }
}
