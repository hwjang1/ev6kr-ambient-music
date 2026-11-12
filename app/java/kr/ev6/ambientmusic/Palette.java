package kr.ev6.ambientmusic;

/**
 * The ambient light's 64 custom colours (the vehicle settings app's picker), as
 * HMG-API's ambient_light command knows them (hmgapi.vehicle.Commands.PALETTE,
 * measured on a US EV6, 260507). The car drops any other RGB, and HMG-API sends the
 * nearest of these, so the app only ever offers and sends these.
 */
final class Palette {
    private Palette() {}

    static final int[] COLORS = {
        0x1043f4, 0x102ef2, 0x1020ef, 0x211cef, 0x2f1cef, 0x401cef, 0x521cef, 0x641cef,
        0x781cef, 0x881def, 0x9b21ef, 0xa923ef, 0xb723ea, 0xc121e0, 0xcc1fd2, 0xd51bc0,
        0xdd17ae, 0xe41399, 0xeb0e99, 0xef0a74, 0xf60660, 0xfa0451, 0xfe0145, 0xff321d,
        0xff4423, 0xff5027, 0xff6930, 0xff7835, 0xff8f3e, 0xffa646, 0xffc04f, 0xffd356,
        0xffe55c, 0xfff362, 0xfefd66, 0xffffff, 0xf4fd66, 0xe8fd66, 0xd9fd66, 0xcbfd63,
        0xb7f962, 0xa8f362, 0x95eb62, 0x84e562, 0x75df62, 0x68dd62, 0x5dde69, 0x51e176,
        0x47e785, 0x3eee98, 0x35f5a9, 0x2cfabd, 0x25facf, 0x1efae1, 0x19faed, 0x14faf9,
        0x10f4fc, 0x10e6fc, 0x10d2fc, 0x10bbfc, 0x10a3fc, 0x108afc, 0x106ffc, 0x1059f8,
    };

    static boolean has(int rgb) {
        for (int c : COLORS) if (c == rgb) return true;
        return false;
    }

    /** "ff321d": the form hmgapid takes (lowercase hex only) */
    static String hex(int rgb) {
        String s = Integer.toHexString(rgb & 0xffffff);
        while (s.length() < 6) s = "0" + s;
        return s;
    }
}
