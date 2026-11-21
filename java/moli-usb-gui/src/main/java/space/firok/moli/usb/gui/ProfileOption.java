package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.domain.DeviceProfile;

import java.util.List;

/**
 * 界面上的 profile 选项。标签是中文或型号名，实际参数仍来自 {@link DeviceProfile}。
 */
public record ProfileOption(String label, String profileName) {

    public static final ProfileOption MINI = new ProfileOption("MINI", DeviceProfile.MINI);
    public static final ProfileOption LITE = new ProfileOption("LITE", DeviceProfile.LITE);
    public static final ProfileOption PRO = new ProfileOption("PRO", DeviceProfile.PRO);
    public static final ProfileOption GENERIC = new ProfileOption("通用", DeviceProfile.GENERIC);

    public static final List<ProfileOption> ALL = List.of(MINI, LITE, PRO, GENERIC);

    public DeviceProfile profile() {
        return DeviceProfile.byName(profileName);
    }

    public static ProfileOption fromProfileName(String profileName) {
        for (ProfileOption option : ALL) {
            if (option.profileName.equals(profileName)) {
                return option;
            }
        }
        return GENERIC;
    }

    @Override
    public String toString() {
        return label;
    }
}
