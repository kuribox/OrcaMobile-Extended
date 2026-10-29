package ru.ytkab0bp.slicebeam.fragment;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ru.ytkab0bp.slicebeam.R;
import ru.ytkab0bp.slicebeam.SliceBeam;
import ru.ytkab0bp.slicebeam.components.BeamAlertDialogBuilder;
import ru.ytkab0bp.slicebeam.config.ConfigObject;
import ru.ytkab0bp.slicebeam.recycler.PreferenceItem;
import ru.ytkab0bp.slicebeam.recycler.SpaceItem;
import ru.ytkab0bp.slicebeam.slic3r.PrintConfigDef;
import ru.ytkab0bp.slicebeam.slic3r.Slic3rLocalization;
import ru.ytkab0bp.slicebeam.utils.FlashforgeDiscovery;
import ru.ytkab0bp.slicebeam.utils.ViewUtils;

public class PrinterConfigFragment extends ProfileListFragment {
    private ConfigObject currentConfig;

    @Override
    protected int getProfileListType() {
        return ConfigObject.PROFILE_LIST_PRINTER;
    }

    @Override
    protected boolean useTabs() {
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        onResetConfig();
    }

    @Override
    protected List<ProfileListItem> getItems(boolean filter) {
        return (List) SliceBeam.CONFIG.printerConfigs;
    }

    @Override
    protected List<OptionElement> getConfigItems() {
        migrateLegacyFlashforgeKeys();
        PrintConfigDef def = PrintConfigDef.getInstance();
        ArrayList<OptionElement> list = new ArrayList<>(Arrays.asList(
                new OptionElement(R.drawable.printer_outline_28, "General"),
                new OptionElement(new SubHeader("Size and coordinates")),
                new OptionElement(def.options.get("printable_area")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(def.options.get("printable_height")),
                new OptionElement(def.options.get("z_offset")),

                new OptionElement(new SubHeader("Capabilities")),
                // TODO: Extruders setting
                new OptionElement(def.options.get("single_extruder_multi_material")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Firmware")),
                new OptionElement(def.options.get("gcode_flavor")),
                // TODO: Thumbnails are not working *yet*
//                new OptionElement(def.options.get("thumbnails")),
                new OptionElement(def.options.get("silent_mode")),
                new OptionElement(def.options.get("remaining_times")),
                new OptionElement(def.options.get("binary_gcode")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Advanced")),
                new OptionElement(def.options.get("use_relative_e_distances")),
                new OptionElement(def.options.get("use_firmware_retraction")),
                new OptionElement(def.options.get("use_volumetric_e")),
                new OptionElement(def.options.get("variable_layer_height")),
                new OptionElement(def.options.get("prefer_clockwise_movements")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(R.drawable.settings_outline_28, "Custom G-code"),
                new OptionElement(new SubHeader("Start G-code")),
                new OptionElement(def.options.get("machine_start_gcode")),
                new OptionElement(def.options.get("autoemit_temperature_commands")),

                new OptionElement(new SubHeader("End G-code")),
                new OptionElement(def.options.get("machine_end_gcode")),

                new OptionElement(new SubHeader("Before layer change G-code")),
                new OptionElement(def.options.get("before_layer_change_gcode")),

                new OptionElement(new SubHeader("After layer change G-code")),
                new OptionElement(def.options.get("layer_change_gcode")),

                new OptionElement(new SubHeader("Tool change G-code")),
                new OptionElement(def.options.get("change_filament_gcode")),

                new OptionElement(new SubHeader("Between objects G-code (for sequential printing)")),
                new OptionElement(def.options.get("between_objects_gcode")),

                new OptionElement(new SubHeader("Color Change G-code")),
                new OptionElement(def.options.get("color_change_gcode")),

                new OptionElement(new SubHeader("Pause Print G-code")),
                new OptionElement(def.options.get("machine_pause_gcode")),

                new OptionElement(new SubHeader("Template Custom G-code")),
                new OptionElement(def.options.get("template_custom_gcode")),

                new OptionElement(R.drawable.note_pen_outline_96, "Machine limits"),
                new OptionElement(new SubHeader("General")),
                new OptionElement(def.options.get("machine_limits_usage")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Maximum feedrates")),
                new OptionElement(def.options.get("machine_max_acceleration_x")),
                new OptionElement(def.options.get("machine_max_acceleration_y")),
                new OptionElement(def.options.get("machine_max_acceleration_z")),
                new OptionElement(def.options.get("machine_max_acceleration_e")),
                new OptionElement(def.options.get("machine_max_acceleration_extruding")),
                new OptionElement(def.options.get("machine_max_acceleration_retracting")),
                // TODO: m_supports_travel_acceleration? new OptionElement(def.options.get("machine_max_acceleration_travel")) <= repetier/reprap/marlin
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Maximum speeds")),
                new OptionElement(def.options.get("machine_max_speed_x")),
                new OptionElement(def.options.get("machine_max_speed_y")),
                new OptionElement(def.options.get("machine_max_speed_z")),
                new OptionElement(def.options.get("machine_max_speed_e")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Jerk limits")),
                new OptionElement(def.options.get("machine_max_jerk_x")),
                new OptionElement(def.options.get("machine_max_jerk_y")),
                new OptionElement(def.options.get("machine_max_jerk_z")),
                new OptionElement(def.options.get("machine_max_jerk_e")),
                new OptionElement(def.options.get("machine_max_junction_deviation")),
                new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                new OptionElement(new SubHeader("Minimum feedrates")),
                new OptionElement(def.options.get("machine_min_extruding_rate")),
                new OptionElement(def.options.get("machine_min_travel_rate"))
        ));

        int count = currentConfig.getExtruderCount();
        for (int i = 0; i < count; i++) {
            int j = count == 1 ? -1 : i;
            list.addAll(Arrays.asList(
                    new OptionElement(R.drawable.hashtag_outline_28, String.format(Slic3rLocalization.getString("Extruder %d"), i + 1)),
                    new OptionElement(new SubHeader("Size")),
                    new OptionElement(def.options.get("nozzle_diameter"), j),
                    new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                    new OptionElement(new SubHeader("Preview")),
                    new OptionElement(def.options.get("extruder_colour"), j),
                    new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                    new OptionElement(new SubHeader("Layer height limits")),
                    new OptionElement(def.options.get("min_layer_height"), j),
                    new OptionElement(def.options.get("max_layer_height"), j),
                    new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                    new OptionElement(new SubHeader("Position (for multi-extruder printers)")),
                    new OptionElement(def.options.get("extruder_offset"), j),
                    new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                    new OptionElement(new SubHeader("Travel lift")),
                    new OptionElement(def.options.get("z_hop"), j),
                    new OptionElement(def.options.get("z_hop_types"), j),
                    new OptionElement(def.options.get("retract_lift_enforce"), j),
                    new OptionElement(def.options.get("travel_ramping_lift"), j),
                    new OptionElement(def.options.get("travel_max_lift"), j),
                    new OptionElement(def.options.get("travel_slope"), j),
                    new OptionElement(def.options.get("travel_lift_before_obstacle"), j),
                    new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                    new OptionElement(new SubHeader("Only lift")),
                    new OptionElement(def.options.get("retract_lift_above"), j),
                    new OptionElement(def.options.get("retract_lift_below"), j),
                    new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                    new OptionElement(new SubHeader("Retraction")),
                    new OptionElement(def.options.get("retraction_length"), j),
                    new OptionElement(def.options.get("retraction_speed"), j),
                    new OptionElement(def.options.get("deretraction_speed"), j),
                    new OptionElement(def.options.get("retract_restart_extra"), j),
                    new OptionElement(def.options.get("retraction_minimum_travel"), j),
                    new OptionElement(def.options.get("retract_when_changing_layer"), j),
                    new OptionElement(def.options.get("wipe"), j),
                    new OptionElement(def.options.get("retract_before_wipe"), j),
                    new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                    new OptionElement(new SubHeader("Retraction for cutting (long retraction when cut)")),
                    new OptionElement(def.options.get("long_retractions_when_cut"), j),
                    new OptionElement(def.options.get("retraction_distances_when_cut"), j),
                    new OptionElement(new SpaceItem(0, ViewUtils.dp(4))),

                    new OptionElement(new SubHeader("Retraction when tool is disabled (advanced settings for multi-extruder setups)")),
                    new OptionElement(def.options.get("retract_length_toolchange"), j),
                    new OptionElement(def.options.get("retract_restart_extra_toolchange"), j)
            ));
        }
        list.addAll(Arrays.asList(
                new OptionElement(R.drawable.note_pen_outline_96, "Notes"),
                new OptionElement(new SubHeader("Notes")),
                new OptionElement(def.options.get("printer_notes")),

                new OptionElement(R.drawable.power_socket_outline_28, "Physical Printer"),
                new OptionElement(new SubHeader("Print Host upload")),
                new OptionElement(def.options.get("host_type")),
                new OptionElement(def.options.get("print_host")),
                // Flashforge keeps its check code in the API key field and takes the serial number
                // from discovery, exactly like the desktop printer dialog.
                new OptionElement(def.options.get("printhost_apikey")),
                new OptionElement(def.options.get("flashforge_serial_number"))
        ));
        // Only meaningful for Flashforge: the broadcast is theirs alone.
        if ("flashforge".equalsIgnoreCase(effective("host_type"))) {
            list.add(new OptionElement(discoverItem(def)));
        }
        list.add(new OptionElement(hostTestItem()));

        return list;
    }

    /** Fills print_host and flashforge_serial_number from the LAN, like the desktop dialog. */
    private PreferenceItem discoverItem(PrintConfigDef def) {
        PreferenceItem item = new PreferenceItem()
                .setTitle(SliceBeam.INSTANCE.getString(R.string.PrinterDiscoverFlashforge))
                .setSubtitle(SliceBeam.INSTANCE.getString(R.string.PrinterDiscoverFlashforgeHint));
        item.setOnClickListener(v -> runDiscovery(def));
        return item;
    }

    private void runDiscovery(PrintConfigDef def) {
        final android.content.Context ctx = getContext();
        if (ctx == null) return;

        final AlertDialog dialog = new BeamAlertDialogBuilder(ctx)
                .setTitle(R.string.PrinterDiscoverFlashforge)
                .setMessage(R.string.PrinterDiscoverFlashforgeRunning)
                .setCancelable(true)
                .create();
        dialog.show();

        new Thread(() -> {
            List<FlashforgeDiscovery.Printer> found = null;
            String error = null;
            try {
                found = FlashforgeDiscovery.discover(10000, 1500, 3);
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final List<FlashforgeDiscovery.Printer> printers = found;
            final String failure = error;
            ViewUtils.postOnMainThread(() -> {
                if (dialog.isShowing()) dialog.dismiss();

                if (failure != null || printers == null || printers.isEmpty()) {
                    new BeamAlertDialogBuilder(ctx)
                            .setTitle(R.string.PrinterDiscoverFlashforge)
                            .setMessage(failure != null ? failure : SliceBeam.INSTANCE.getString(R.string.PrinterDiscoverFlashforgeNone))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }

                String[] labels = new String[printers.size()];
                for (int i = 0; i < printers.size(); i++) {
                    labels[i] = printers.get(i).toString();
                }
                new BeamAlertDialogBuilder(ctx)
                        .setTitle(R.string.PrinterDiscoverFlashforgePick)
                        .setItems(labels, (d, which) -> applyDiscoveredPrinter(def, printers.get(which)))
                        .show();
            });
        }, "flashforge-discovery").start();
    }

    private void applyDiscoveredPrinter(PrintConfigDef def, FlashforgeDiscovery.Printer printer) {
        updateConfigField(def.options.get("host_type"), -1, "flashforge");
        updateConfigField(def.options.get("print_host"), -1, printer.ip);
        if (printer.serial != null && !printer.serial.isEmpty()) {
            updateConfigField(def.options.get("flashforge_serial_number"), -1, printer.serial);
        }
        onUpdateConfigItems();
    }

    private PreferenceItem hostTestItem() {
        PreferenceItem item = new PreferenceItem()
                .setTitle(SliceBeam.INSTANCE.getString(R.string.MenuPrinterTestConnection))
                .setSubtitle(describeHost());
        item.setOnClickListener(v -> runHostTest());
        return item;
    }

    /**
     * Earlier builds of this fork stored the Flashforge serial in printhost_user and the check code in
     * printhost_password. Profiles saved then would look empty to the current code, so copy the values
     * across (only when the current key has nothing) before the screen reads them.
     */
    private void migrateLegacyFlashforgeKeys() {
        if (currentConfig == null) return;
        boolean changed = false;
        changed |= copyIfMissing("printhost_user", "flashforge_serial_number");
        changed |= copyIfMissing("printhost_password", "printhost_apikey");
        if (changed) {
            ConfigObject stored = SliceBeam.CONFIG.findPrinter(currentConfig.getTitle());
            if (stored != null && stored != currentConfig) {
                stored.values.putAll(currentConfig.values);
            }
            SliceBeam.saveConfig();
        }
    }

    private boolean copyIfMissing(String legacyKey, String currentKey) {
        String current = currentConfig.get(currentKey);
        if (!blank(current)) return false;
        String legacy = currentConfig.get(legacyKey);
        if (blank(legacy)) return false;
        currentConfig.values.put(currentKey, legacy);
        return true;
    }

    /**
     * Value the user currently sees: pending edits (typed fields, discovery results) win over the saved
     * profile. Reading currentConfig here was the bug that made the test claim fields were empty right
     * after discovery had filled the serial number in.
     */
    private String effective(String key) {
        if (key == null) return null;
        if (diffObject != null && diffObject.has(key)) {
            return diffObject.get(key);
        }
        return currentConfig != null ? currentConfig.get(key) : null;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String describeHost() {
        if (currentConfig == null) return "";
        String type = effective("host_type");
        String host = effective("print_host");
        if (blank(type)) type = "octoprint";
        if (blank(host)) {
            return SliceBeam.INSTANCE.getString(R.string.MenuPrinterTestNoHostHint);
        }
        StringBuilder sb = new StringBuilder(type).append(" · ").append(host.trim());
        if ("flashforge".equalsIgnoreCase(type)) {
            boolean http = !blank(effective("flashforge_serial_number")) && !blank(effective("printhost_apikey"));
            sb.append(" · ").append(SliceBeam.INSTANCE.getString(
                    http ? R.string.PrinterTestTransportHttp : R.string.PrinterTestTransportSerial));
        }
        return sb.toString();
    }

    /** Returns the resource id of the exact missing field, or 0 when the host is complete. */
    private int missingHostField(String type, String host, String serialNumber, String checkCode) {
        if (blank(host)) {
            return R.string.PrinterTestMissingHost;
        }
        // Flashforge without serial/check code is not an error: the legacy console protocol on
        // TCP 8899 needs no credentials, which is what desktop OrcaSlicer falls back to.
        return 0;
    }

    private void runHostTest() {
        final android.content.Context ctx = getContext();
        if (ctx == null) return;

        final String type = effective("host_type");
        final String host = effective("print_host");
        final String apiKey = effective("printhost_apikey");
        final String serialNumber = effective("flashforge_serial_number");

        int missing = missingHostField(type, host, serialNumber, apiKey);
        if (missing != 0) {
            new BeamAlertDialogBuilder(ctx)
                    .setTitle(R.string.MenuPrinterTestFailedTitle)
                    .setMessage(missing)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final AlertDialog dialog = new BeamAlertDialogBuilder(ctx)
                .setTitle(R.string.MenuPrinterTestConnection)
                .setMessage(R.string.MenuPrinterTestRunning)
                .setCancelable(true)
                .create();
        dialog.show();

        ru.ytkab0bp.slicebeam.utils.PrintHostTester.test(type, host, apiKey, serialNumber, (ok, message) ->
                ViewUtils.postOnMainThread(() -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    new BeamAlertDialogBuilder(ctx)
                            .setTitle(ok ? R.string.MenuPrinterTestOKTitle : R.string.MenuPrinterTestFailedTitle)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }));
    }

    @Override
    protected void cloneCurrentProfile() {
        ConfigObject obj = new ConfigObject(SliceBeam.INSTANCE.getString(R.string.SettingsProfileCopy, currentConfig.getTitle()));
        obj.values.putAll(currentConfig.values);
        currentConfig = new ConfigObject(obj);

        SliceBeam.CONFIG.printerConfigs.add(obj);
        SliceBeam.CONFIG.presets.put("printer", obj.getTitle());
        SliceBeam.saveConfig();
        SliceBeam.getCurrentConfigFile().delete();

        currentConfig = new ConfigObject(obj);
        dropdownView.setTitle(getCurrentConfig().getTitle());
    }

    @Override
    protected void deleteCurrentProfile() {
        SliceBeam.CONFIG.printerConfigs.remove(SliceBeam.CONFIG.findPrinter(currentConfig.getTitle()));
        selectItem(getItems(true).get(0));
        dropdownView.setTitle(getCurrentConfig().getTitle());
    }

    @Override
    protected void onApplyConfig(String title) {
        ConfigObject obj = SliceBeam.CONFIG.findPrinter(currentConfig.getTitle());
        obj.setTitle(title);
        obj.values.putAll(currentConfig.values);
        currentConfig.setTitle(title);

        SliceBeam.CONFIG.presets.put("printer", title);
        SliceBeam.saveConfig();
        SliceBeam.getCurrentConfigFile().delete();

        dropdownView.setTitle(title);
    }

    @Override
    protected void onResetConfig() {
        ConfigObject printer = SliceBeam.CONFIG.findPrinter(SliceBeam.CONFIG.presets.get("printer"));
        if (printer == null) {
            printer = !SliceBeam.CONFIG.printerConfigs.isEmpty() ? SliceBeam.CONFIG.printerConfigs.get(0) : ConfigObject.createCustomPrinterProfile();
        }
        currentConfig = new ConfigObject(printer);
    }

    @Override
    protected ConfigObject getCurrentConfig() {
        return currentConfig;
    }

    @Override
    protected int getTitle() {
        return R.string.SlotPrinterConfigTooltip;
    }

    @Override
    protected void selectItem(ProfileListItem item) {
        currentConfig = new ConfigObject((ConfigObject) item);
        SliceBeam.CONFIG.presets.put("printer", item.getTitle());

        // TODO: Reset print/filament profiles, maybe physical profiles?
        SliceBeam.saveConfig();
    }
}
