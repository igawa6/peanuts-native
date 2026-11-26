package io.github.davdxpx.mistel.mncf;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Ed25519 signature verification (RFC 8032) in plain Java, so that the reader needs no security
 * provider that a platform may lack.
 *
 * <p>Verification checks [S]B = R + [k]A without the cofactor, refuses S &gt;= L, and refuses
 * encodings of y &gt;= p and points that are not on the curve. It works on public data only, so
 * it does not need to run in constant time.
 */
final class Ed25519 {

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger L =
            BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D =
            BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P);
    private static final BigInteger SQRT_M1 =
            TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger[] NEUTRAL = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
    private static final BigInteger[] BASE;

    static {
        BigInteger y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P);
        BigInteger x = recoverX(y, 0);
        BASE = new BigInteger[] {x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    private Ed25519() {
    }

    private static BigInteger inv(BigInteger x) {
        return x.modPow(P.subtract(TWO), P);
    }

    /** Points in extended coordinates (X, Y, Z, T) with x = X/Z, y = Y/Z and x * y = T/Z. */
    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = TWO.multiply(p[3]).multiply(q[3]).multiply(D).mod(P);
        BigInteger d = TWO.multiply(p[2]).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a);
        BigInteger f = d.subtract(c);
        BigInteger g = d.add(c);
        BigInteger h = b.add(a);
        return new BigInteger[] {e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] mul(BigInteger s, BigInteger[] p) {
        BigInteger[] q = NEUTRAL;
        BigInteger[] addend = p;
        for (int i = 0; i < s.bitLength(); i++) {
            if (s.testBit(i)) {
                q = add(q, addend);
            }
            addend = add(addend, addend);
        }
        return q;
    }

    private static boolean equal(BigInteger[] p, BigInteger[] q) {
        return p[0].multiply(q[2]).subtract(q[0].multiply(p[2])).mod(P).signum() == 0
                && p[1].multiply(q[2]).subtract(q[1].multiply(p[2])).mod(P).signum() == 0;
    }

    private static BigInteger recoverX(BigInteger y, int sign) {
        if (y.compareTo(P) >= 0) {
            return null;
        }
        BigInteger yy = y.multiply(y);
        BigInteger x2 = yy.subtract(BigInteger.ONE).multiply(inv(D.multiply(yy).add(BigInteger.ONE))).mod(P);
        if (x2.signum() == 0) {
            return sign != 0 ? null : BigInteger.ZERO;
        }
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) {
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) {
            return null;
        }
        if ((x.testBit(0) ? 1 : 0) != sign) {
            x = P.subtract(x);
        }
        return x;
    }

    private static BigInteger littleEndian(byte[] data, int from, int length) {
        byte[] reversed = new byte[length];
        for (int i = 0; i < length; i++) {
            reversed[i] = data[from + length - 1 - i];
        }
        return new BigInteger(1, reversed);
    }

    private static BigInteger[] decompress(byte[] data, int from) {
        BigInteger y = littleEndian(data, from, 32);
        int sign = y.testBit(255) ? 1 : 0;
        y = y.clearBit(255);
        BigInteger x = recoverX(y, sign);
        if (x == null) {
            return null;
        }
        return new BigInteger[] {x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    private static BigInteger hashInt(byte[]... parts) {
        MessageDigest sha512;
        try {
            sha512 = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is not available", e);
        }
        for (byte[] part : parts) {
            sha512.update(part);
        }
        byte[] h = sha512.digest();
        return littleEndian(h, 0, h.length).mod(L);
    }

    /** Whether signature is a valid signature of message by the 32-byte public key. */
    static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey.length != 32 || signature.length != 64) {
            return false;
        }
        BigInteger[] a = decompress(publicKey, 0);
        BigInteger[] r = decompress(signature, 0);
        if (a == null || r == null) {
            return false;
        }
        BigInteger s = littleEndian(signature, 32, 32);
        if (s.compareTo(L) >= 0) {
            return false;
        }
        byte[] rBytes = new byte[32];
        System.arraycopy(signature, 0, rBytes, 0, 32);
        BigInteger k = hashInt(rBytes, publicKey, message);
        return equal(mul(s, BASE), add(r, mul(k, a)));
    }
}
