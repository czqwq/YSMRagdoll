package com.Lilith.ysmragdoll.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.Lilith.ysmragdoll.YsmRagdollLog;
import com.Lilith.ysmragdoll.config.YsmRagdollConfig;

/**
 * 左侧分类导航、右侧设置内容的单页配置界面（1.7.10 版）。
 *
 * <p>
 * 上游 1.20.1 版本使用 {@code Screen} + {@code EditBox}/{@code Checkbox}；
 * 1.7.10 只提供 {@link GuiButton} 和 {@link GuiTextField}，因此这里用带开关文字的
 * 按钮代替复选框，并自行实现滚动区域、可见性裁剪和滚轮翻页。行为保持一致：
 * 取消不保存草稿，应用写回并落盘，非法输入保留文本并提示修正。
 * </p>
 */
public final class YsmRagdollSettingsScreen extends GuiScreen {

    private static final int PANEL_COLOR = 0xE8101114;
    private static final int NAVIGATION_COLOR = 0xD9181A1F;
    private static final int CONTENT_COLOR = 0xC8141519;
    private static final int DIVIDER_COLOR = 0xFF3B3E45;
    private static final int LABEL_COLOR = 0xFFE2E2E2;
    private static final int MUTED_COLOR = 0xFF9A9DA5;
    private static final int ERROR_COLOR = 0xFFFF6B6B;
    private static final int SUCCESS_COLOR = 0xFF70D68A;
    private static final double MIN_OFFSET = -4.0D;
    private static final double MAX_OFFSET = 4.0D;
    private static final int ROW_HEIGHT = 22;
    private static final int FIELD_HEIGHT = 18;

    private enum Category {

        GENERAL("screen.ysmragdoll.category.general"),
        ADVANCED("screen.ysmragdoll.category.advanced"),
        MANAGEMENT("screen.ysmragdoll.category.management"),
        TESTING("screen.ysmragdoll.category.testing");

        private final String key;

        Category(String key) {
            this.key = key;
        }

        private String title() {
            return I18n.format(key);
        }
    }

    private final GuiScreen parent;
    private Category category = Category.GENERAL;
    private Draft draft;
    private String status = "";
    private boolean statusIsError;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int navigationWidth;
    private int contentLeft;
    private int contentWidth;
    private int contentTop;
    private int footerTop;
    private int contentBottom;
    private int contentHeight;
    private int scroll;
    private boolean draggingScrollbar;
    private double scrollbarGrabOffset;

    private final List<Label> labels = new ArrayList<>();
    private final List<ContentWidget> contentWidgets = new ArrayList<>();
    private final List<GuiButton> navigationButtons = new ArrayList<>();
    private final List<GuiButton> footerButtons = new ArrayList<>();
    private final List<GuiTextField> fields = new ArrayList<>();

    private GuiTextField lifetimeField;
    private GuiTextField maximumField;
    private GuiTextField easyPushField;
    private GuiTextField groundFrictionField;
    private GuiTextField explosionPushField;
    private final GuiTextField[] renderOffsetFields = new GuiTextField[3];
    private final GuiTextField[] collisionOffsetFields = new GuiTextField[3];

    public YsmRagdollSettingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        if (draft == null) {
            draft = Draft.load();
        }
        labels.clear();
        contentWidgets.clear();
        navigationButtons.clear();
        footerButtons.clear();
        fields.clear();
        buttonList.clear();
        draggingScrollbar = false;
        scroll = 0;

        panelWidth = Math.min(620, width - 16);
        panelHeight = Math.min(390, height - 16);
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        navigationWidth = Math.min(112, Math.max(72, panelWidth / 5));
        contentLeft = panelLeft + navigationWidth + 12;
        contentWidth = panelWidth - navigationWidth - 28;
        contentTop = panelTop + 48;
        footerTop = panelTop + panelHeight - 28;

        int navTop = panelTop + 48;
        int index = 0;
        for (Category target : Category.values()) {
            GuiButton button = new GuiButton(index++, panelLeft + 10, navTop + index * 0, 0, 0, "");
            button.displayString = target.title();
            button.xPosition = panelLeft + 10;
            button.yPosition = navTop + (index - 1) * 26;
            button.width = navigationWidth - 20;
            button.height = 20;
            button.enabled = category != target;
            navigationButtons.add(button);
            buttonList.add(button);
        }

        if (category == Category.GENERAL) {
            addGeneralControls();
        } else if (category == Category.ADVANCED) {
            addAdvancedControls();
        } else if (category == Category.MANAGEMENT) {
            addManagementLabels();
        } else {
            addTestingControls();
        }
        addFooterButtons();
        positionContent();
    }

    private void addFooterButtons() {
        int buttonWidth = 60;
        int gap = 6;
        int right = panelLeft + panelWidth - 10;
        int y = footerTop + 4;
        footerButtons
            .add(addFixedButton(100, right - buttonWidth, y, buttonWidth, 20, I18n.format("gui.ysmragdoll.cancel")));
        footerButtons.add(
            addFixedButton(
                101,
                right - buttonWidth * 2 - gap,
                y,
                buttonWidth,
                20,
                I18n.format("gui.ysmragdoll.apply")));
        footerButtons.add(
            addFixedButton(
                102,
                right - buttonWidth * 3 - gap * 2,
                y,
                buttonWidth,
                20,
                I18n.format("gui.ysmragdoll.confirm")));
    }

    private GuiButton addFixedButton(int id, int x, int y, int buttonWidth, int buttonHeight, String label) {
        GuiButton button = new GuiButton(id, x, y, buttonWidth, buttonHeight, label);
        buttonList.add(button);
        return button;
    }

    private void addGeneralControls() {
        int fieldWidth = Math.min(260, contentWidth);
        int y = label("screen.ysmragdoll.general.lifetime", contentLeft, 0, fieldWidth);
        lifetimeField = integerField(
            contentLeft,
            y,
            fieldWidth,
            draft.lifetimeSeconds,
            "screen.ysmragdoll.general.lifetime");
        y += FIELD_HEIGHT + 14;
        y = toggle("screen.ysmragdoll.general.manual_removal", contentLeft, y, fieldWidth, draft.manualRemoval, 200);
        y = label("screen.ysmragdoll.general.maximum", contentLeft, y + 4, fieldWidth);
        maximumField = integerField(
            contentLeft,
            y,
            fieldWidth,
            draft.maximumRagdolls,
            "screen.ysmragdoll.general.maximum");
        contentHeight = y + FIELD_HEIGHT + 14;
    }

    private void addTestingControls() {
        int y = label("screen.ysmragdoll.testing.gravity_hint", contentLeft, 0, contentWidth);
        GuiButton toggle = new GuiButton(210, contentLeft, 0, Math.min(260, contentWidth), 20, gravityGunLabel());
        contentWidgets.add(new ContentWidget(toggle, y));
        y += ROW_HEIGHT + 12;
        contentHeight = y;
    }

    private String gravityGunLabel() {
        return I18n.format(
            "screen.ysmragdoll.testing.gravity_gun",
            I18n.format(
                YsmRagdollConfig.gravityGunMode() ? "options.ysmragdoll.enabled" : "options.ysmragdoll.disabled"));
    }

    private void addManagementLabels() {
        labels.clear();
        List<ClientRagdollManager.ManagementEntry> entries = ClientRagdollManager.managementEntries();
        int y = labelRaw(
            I18n.format("screen.ysmragdoll.management.count", entries.size()),
            contentLeft,
            0,
            contentWidth);
        y = label("screen.ysmragdoll.management.hint", contentLeft, y + 4, contentWidth);
        if (entries.isEmpty()) {
            y = label("screen.ysmragdoll.management.empty", contentLeft, y + 8, contentWidth);
        }
        for (ClientRagdollManager.ManagementEntry entry : entries) {
            String playerId = entry.playerId();
            String shortId = playerId.length() > 8 ? playerId.substring(0, 8) : playerId;
            y = labelRaw(
                I18n.format("screen.ysmragdoll.management.entry", entry.id(), shortId),
                contentLeft,
                y + 8,
                contentWidth);
            String expiry;
            if (entry.manualRemoval()) {
                expiry = I18n.format("screen.ysmragdoll.management.manual");
            } else if (entry.remainingMillis() < 0) {
                expiry = I18n.format("screen.ysmragdoll.management.permanent");
            } else {
                expiry = I18n
                    .format("screen.ysmragdoll.management.countdown", (entry.remainingMillis() + 999L) / 1000L);
            }
            y = labelRaw(expiry, contentLeft, y, contentWidth);
        }
        contentHeight = y + 8;
    }

    private void addAdvancedControls() {
        int controlsTop = label("screen.ysmragdoll.advanced.create_hint", contentLeft, 0, contentWidth);
        GuiButton create = new GuiButton(
            220,
            contentLeft,
            0,
            Math.min(260, contentWidth),
            20,
            I18n.format("screen.ysmragdoll.advanced.create"));
        contentWidgets.add(new ContentWidget(create, controlsTop));
        controlsTop += ROW_HEIGHT + 12;

        boolean twoColumns = contentWidth >= 420;
        int gap = 20;
        int fieldWidth = twoColumns ? (contentWidth - gap) / 2 : contentWidth;
        int rightColumnLeft = twoColumns ? contentLeft + fieldWidth + gap : contentLeft;

        int y = label("screen.ysmragdoll.advanced.easy_push", contentLeft, controlsTop, fieldWidth);
        easyPushField = integerField(
            contentLeft,
            y,
            fieldWidth,
            draft.easyPushIndex,
            "screen.ysmragdoll.advanced.easy_push");
        y += FIELD_HEIGHT + 14;
        y = label("screen.ysmragdoll.advanced.friction", contentLeft, y, fieldWidth);
        groundFrictionField = integerField(
            contentLeft,
            y,
            fieldWidth,
            draft.groundFriction,
            "screen.ysmragdoll.advanced.friction");
        y += FIELD_HEIGHT + 14;
        y = label("screen.ysmragdoll.advanced.explosion_push", contentLeft, y, fieldWidth);
        explosionPushField = integerField(
            contentLeft,
            y,
            fieldWidth,
            draft.explosionPushIndex,
            "screen.ysmragdoll.advanced.explosion_push");
        y += FIELD_HEIGHT + 14;
        y = toggle(
            "screen.ysmragdoll.advanced.collision_boxes",
            contentLeft,
            y,
            fieldWidth,
            draft.showCollisionBoxes,
            201);
        y = toggle("screen.ysmragdoll.advanced.intensive_test", contentLeft, y, fieldWidth, draft.intensiveTest, 202);
        int leftHeight = y;

        int rightY = offsetRow(
            "screen.ysmragdoll.advanced.render_offset",
            rightColumnLeft,
            twoColumns ? controlsTop : leftHeight,
            fieldWidth,
            draft.renderOffsets,
            renderOffsetFields);
        rightY = offsetRow(
            "screen.ysmragdoll.advanced.collision_offset",
            rightColumnLeft,
            rightY,
            fieldWidth,
            draft.collisionOffsets,
            collisionOffsetFields);
        contentHeight = Math.max(leftHeight, rightY);
    }

    private int offsetRow(String key, int x, int y, int availableWidth, double[] values, GuiTextField[] target) {
        int top = label(key, x, y, availableWidth);
        int axisWidth = Math.max(30, (availableWidth - 16) / 3);
        for (int axis = 0; axis < 3; axis++) {
            int axisX = x + axis * (axisWidth + 8);
            String axisName = "XYZ".substring(axis, axis + 1);
            labels.add(new Label(axisName, axisX, top, MUTED_COLOR));
            target[axis] = decimalField(axisX, top + 12, axisWidth, values[axis], key, axisName);
        }
        return top + 12 + FIELD_HEIGHT + 14;
    }

    /** {@code y} 是相对内容区顶部的偏移；最终位置由 {@link #positionContent()} 统一计算。 */
    private GuiTextField integerField(int x, int y, int fieldWidth, int value, String labelKey) {
        GuiTextField field = new GuiTextField(fontRendererObj, x, contentTop + y, fieldWidth, FIELD_HEIGHT);
        field.setMaxStringLength(12);
        field.setText(Integer.toString(value));
        contentWidgets.add(new ContentWidget(field, y));
        fields.add(field);
        return field;
    }

    private GuiTextField decimalField(int x, int y, int fieldWidth, double value, String labelKey, String axis) {
        GuiTextField field = new GuiTextField(fontRendererObj, x, contentTop + y, fieldWidth, FIELD_HEIGHT);
        field.setMaxStringLength(12);
        field.setText(formatOffset(value));
        contentWidgets.add(new ContentWidget(field, y));
        fields.add(field);
        return field;
    }

    private int toggle(String key, int x, int y, int availableWidth, boolean value, int id) {
        int bottom = label(key, x, y, availableWidth);
        GuiButton button = new GuiButton(
            id,
            x,
            contentTop + y,
            Math.min(100, availableWidth),
            20,
            I18n.format(value ? "options.ysmragdoll.enabled" : "options.ysmragdoll.disabled"));
        contentWidgets.add(new ContentWidget(button, y));
        return Math.max(y + 20, bottom) + 8;
    }

    private int label(String key, int x, int y, int availableWidth) {
        return labelRaw(I18n.format(key), x, y, availableWidth);
    }

    private int labelRaw(String text, int x, int y, int availableWidth) {
        for (String line : wrap(text, availableWidth)) {
            labels.add(new Label(line, x, y, LABEL_COLOR));
            y += fontRendererObj.FONT_HEIGHT + 1;
        }
        return y + 4;
    }

    @SuppressWarnings("unchecked")
    private List<String> wrap(String text, int availableWidth) {
        return fontRendererObj.listFormattedStringToWidth(text, Math.max(20, availableWidth));
    }

    private void positionContent() {
        int statusHeight = status.isEmpty() ? 0
            : wrap(status, panelWidth - 24).size() * (fontRendererObj.FONT_HEIGHT + 1) + 8;
        contentBottom = footerTop - Math.max(26, statusHeight);
        scroll = Math.max(0, Math.min(scroll, maximumScroll()));
        buttonList.removeAll(contentButtons());
        for (ContentWidget entry : contentWidgets) {
            int y = contentTop + entry.relativeY - scroll;
            if (entry.field != null) {
                entry.field.yPosition = y;
                entry.field.setVisible(isInside(y, FIELD_HEIGHT));
            } else {
                entry.button.yPosition = y;
                if (isInside(y, entry.button.height)) {
                    buttonList.add(entry.button);
                    entry.button.enabled = true;
                }
            }
        }
        for (GuiTextField field : fields) {
            field.setVisible(field.getVisible() && isInside(field.yPosition, FIELD_HEIGHT));
        }
        for (Label label : labels) {
            label.drawY = contentTop + label.relativeY - scroll;
            label.visible = isInside(label.drawY, fontRendererObj.FONT_HEIGHT);
        }
    }

    private List<GuiButton> contentButtons() {
        List<GuiButton> result = new ArrayList<>();
        for (ContentWidget entry : contentWidgets) {
            if (entry.button != null) {
                result.add(entry.button);
            }
        }
        return result;
    }

    private boolean isInside(int y, int height) {
        return y >= contentTop && y + height <= contentBottom;
    }

    private int maximumScroll() {
        return Math.max(0, contentHeight - (contentBottom - contentTop));
    }

    private int scrollbarThumbHeight() {
        int trackHeight = contentBottom - contentTop;
        if (contentHeight <= 0) {
            return trackHeight;
        }
        return Math.max(12, trackHeight * trackHeight / contentHeight);
    }

    private int scrollbarThumbTop() {
        int maximum = maximumScroll();
        if (maximum <= 0) {
            return contentTop;
        }
        return contentTop + (contentBottom - contentTop - scrollbarThumbHeight()) * scroll / maximum;
    }

    private void dragScrollbar(int mouseY) {
        int travel = contentBottom - contentTop - scrollbarThumbHeight();
        scroll = travel <= 0 ? 0
            : (int) Math.round((mouseY - contentTop - scrollbarGrabOffset) * (double) maximumScroll() / travel);
        positionContent();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || maximumScroll() <= 0) {
            return;
        }
        if (GravityGunController.scroll(wheel / 120.0D)) {
            return;
        }
        scroll = MathHelper.clamp_int(scroll - wheel / 120 * 14, 0, maximumScroll());
        positionContent();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0 && maximumScroll() > 0
            && mouseX >= panelLeft + panelWidth - 11
            && mouseX <= panelLeft + panelWidth - 2
            && mouseY >= contentTop
            && mouseY <= contentBottom) {
            int thumbTop = scrollbarThumbTop();
            int thumbHeight = scrollbarThumbHeight();
            scrollbarGrabOffset = mouseY >= thumbTop && mouseY <= thumbTop + thumbHeight ? mouseY - thumbTop
                : thumbHeight / 2.0D;
            draggingScrollbar = true;
            dragScrollbar(mouseY);
            return;
        }
        for (GuiTextField field : fields) {
            if (field.getVisible()) {
                field.mouseClicked(mouseX, mouseY, button);
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (draggingScrollbar && button >= 0) {
            draggingScrollbar = false;
        }
        if (draggingScrollbar) {
            dragScrollbar(mouseY);
        }
        super.mouseMovedOrUp(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
            return;
        }
        for (GuiTextField field : fields) {
            if (field.getVisible() && field.isFocused()) {
                field.textboxKeyTyped(typedChar, keyCode);
                clearStatus();
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id >= 0 && button.id < Category.values().length) {
            category = Category.values()[button.id];
            scroll = 0;
            initGui();
            return;
        }
        switch (button.id) {
            case 100:
                close();
                return;
            case 101:
                applyDraft(false);
                return;
            case 102:
                applyDraft(true);
                return;
            case 200:
                draft.manualRemoval = !draft.manualRemoval;
                button.displayString = I18n
                    .format(draft.manualRemoval ? "options.ysmragdoll.enabled" : "options.ysmragdoll.disabled");
                clearStatus();
                return;
            case 201:
                draft.showCollisionBoxes = !draft.showCollisionBoxes;
                button.displayString = I18n
                    .format(draft.showCollisionBoxes ? "options.ysmragdoll.enabled" : "options.ysmragdoll.disabled");
                clearStatus();
                return;
            case 202:
                draft.intensiveTest = !draft.intensiveTest;
                button.displayString = I18n
                    .format(draft.intensiveTest ? "options.ysmragdoll.enabled" : "options.ysmragdoll.disabled");
                ClientIntensiveLogger.reset();
                clearStatus();
                return;
            case 210:
                boolean enabled = !YsmRagdollConfig.gravityGunMode();
                YsmRagdollConfig.gravityGunMode(enabled);
                YsmRagdollConfig.save();
                if (!enabled) {
                    GravityGunController.release();
                }
                button.displayString = gravityGunLabel();
                positionContent();
                return;
            case 220:
                createTestRagdoll();
                return;
            default:
                super.actionPerformed(button);
        }
    }

    private void createTestRagdoll() {
        try {
            ClientRagdollManager.TestSpawnResult result = ClientRagdollManager.createFromCurrentPlayer();
            String key;
            switch (result) {
                case CREATED:
                    key = "created";
                    break;
                case STATIC_CREATED:
                    key = "static_created";
                    break;
                case NO_PLAYER:
                    key = "no_player";
                    break;
                case DISABLED:
                    key = "disabled";
                    break;
                case BELOW_VOID:
                    key = "below_void";
                    break;
                default:
                    key = "capture_failed";
                    break;
            }
            status = I18n.format(
                "screen.ysmragdoll.spawn." + key,
                ClientRagdollManager.ragdollCount(),
                ClientRagdollManager.physicsRagdollCount());
            statusIsError = result != ClientRagdollManager.TestSpawnResult.CREATED;
        } catch (RuntimeException | LinkageError exception) {
            YsmRagdollLog.warn("手动创建测试布娃娃失败", exception);
            status = I18n.format("screen.ysmragdoll.spawn.capture_failed");
            statusIsError = true;
        }
        positionContent();
    }

    private void applyDraft(boolean closeAfterwards) {
        try {
            Draft updated = new Draft();
            updated.lifetimeSeconds = parseInteger(
                lifetimeField,
                0,
                Integer.MAX_VALUE,
                "screen.ysmragdoll.general.lifetime");
            updated.maximumRagdolls = parseInteger(
                maximumField,
                0,
                YsmRagdollConfig.RAGDOLL_LIMIT,
                "screen.ysmragdoll.general.maximum");
            updated.easyPushIndex = parseInteger(easyPushField, 0, 100, "screen.ysmragdoll.advanced.easy_push");
            updated.groundFriction = parseInteger(groundFrictionField, 0, 100, "screen.ysmragdoll.advanced.friction");
            updated.explosionPushIndex = parseInteger(
                explosionPushField,
                0,
                100,
                "screen.ysmragdoll.advanced.explosion_push");
            for (int axis = 0; axis < 3; axis++) {
                updated.collisionOffsets[axis] = parseOffset(
                    collisionOffsetFields[axis],
                    "screen.ysmragdoll.advanced.collision_offset");
                updated.renderOffsets[axis] = parseOffset(
                    renderOffsetFields[axis],
                    "screen.ysmragdoll.advanced.render_offset");
            }
            updated.manualRemoval = draft.manualRemoval;
            updated.showCollisionBoxes = draft.showCollisionBoxes;
            updated.intensiveTest = draft.intensiveTest;
            draft = updated;
            draft.write();
            YsmRagdollConfig.save();
            status = I18n.format("screen.ysmragdoll.applied");
            statusIsError = false;
            if (closeAfterwards) {
                close();
                return;
            }
            positionContent();
        } catch (IllegalArgumentException exception) {
            status = exception.getMessage();
            statusIsError = true;
            positionContent();
        }
    }

    private int parseInteger(GuiTextField field, int minimum, int maximum, String labelKey)
        throws IllegalArgumentException {
        if (field == null) {
            return minimum;
        }
        String text = field.getText()
            .trim();
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw invalid(labelKey, Integer.toString(minimum), Integer.toString(maximum));
        }
        if (value < minimum || value > maximum) {
            throw invalid(labelKey, Integer.toString(minimum), Integer.toString(maximum));
        }
        return value;
    }

    private double parseOffset(GuiTextField field, String labelKey) throws IllegalArgumentException {
        if (field == null) {
            return 0.0D;
        }
        double value;
        try {
            value = Double.parseDouble(
                field.getText()
                    .trim()
                    .replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw invalid(labelKey, formatOffset(MIN_OFFSET), formatOffset(MAX_OFFSET));
        }
        if (Double.isNaN(value) || value < MIN_OFFSET || value > MAX_OFFSET) {
            throw invalid(labelKey, formatOffset(MIN_OFFSET), formatOffset(MAX_OFFSET));
        }
        return value;
    }

    private IllegalArgumentException invalid(String labelKey, String minimum, String maximum) {
        return new IllegalArgumentException(
            I18n.format("screen.ysmragdoll.invalid", I18n.format(labelKey), minimum + " - " + maximum));
    }

    private void clearStatus() {
        status = "";
        statusIsError = false;
        positionContent();
    }

    private void close() {
        mc.displayGuiScreen(parent);
    }

    /** 保持单人游戏继续运行，方便连续创建性能样本。 */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        if (category == Category.MANAGEMENT) {
            addManagementLabels();
            positionContent();
        }
        for (GuiTextField field : fields) {
            field.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_COLOR);
        drawRect(panelLeft, panelTop, panelLeft + navigationWidth, panelTop + panelHeight, NAVIGATION_COLOR);
        drawRect(contentLeft - 6, panelTop + 8, contentLeft - 5, panelTop + panelHeight - 8, DIVIDER_COLOR);
        drawRect(contentLeft - 4, contentTop - 6, panelLeft + panelWidth - 12, contentTop - 5, CONTENT_COLOR);
        drawCenteredString(
            fontRendererObj,
            I18n.format("screen.ysmragdoll.title"),
            panelLeft + panelWidth / 2,
            panelTop + 20,
            LABEL_COLOR);
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (Label label : labels) {
            if (label.visible) {
                fontRendererObj.drawString(label.text, label.x, label.drawY, label.color);
            }
        }
        for (GuiTextField field : fields) {
            if (field.getVisible()) {
                field.drawTextBox();
            }
        }
        drawScrollbar();
        drawStatus();
    }

    private void drawScrollbar() {
        if (maximumScroll() <= 0) {
            return;
        }
        int trackLeft = panelLeft + panelWidth - 10;
        drawRect(trackLeft, contentTop, trackLeft + 6, contentBottom, 0x60000000);
        int thumbTop = scrollbarThumbTop();
        drawRect(trackLeft, thumbTop, trackLeft + 6, thumbTop + scrollbarThumbHeight(), 0xFF7A7F8A);
    }

    private void drawStatus() {
        if (status.isEmpty()) {
            return;
        }
        int y = footerTop - 18;
        for (String line : wrap(status, panelWidth - 24)) {
            fontRendererObj.drawString(line, panelLeft + 12, y, statusIsError ? ERROR_COLOR : SUCCESS_COLOR);
            y += fontRendererObj.FONT_HEIGHT + 1;
        }
    }

    private static String formatOffset(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** 文本行；{@code relativeY} 相对内容区顶部，{@code drawY} 由滚动偏移算出。 */
    private static final class Label {

        final String text;
        final int x;
        final int relativeY;
        final int color;
        int drawY;
        boolean visible;

        Label(String text, int x, int y, int color) {
            this.text = text;
            this.x = x;
            this.relativeY = y;
            this.color = color;
        }
    }

    private static final class ContentWidget {

        final GuiButton button;
        final GuiTextField field;
        final int relativeY;

        ContentWidget(GuiButton button, int relativeY) {
            this.button = button;
            this.field = null;
            this.relativeY = relativeY;
        }

        ContentWidget(GuiTextField field, int relativeY) {
            this.button = null;
            this.field = field;
            this.relativeY = relativeY;
        }
    }

    /** 界面草稿；只有点击应用或确定才会写回配置。 */
    private static final class Draft {

        int lifetimeSeconds;
        boolean manualRemoval;
        int maximumRagdolls;
        int easyPushIndex;
        int groundFriction;
        int explosionPushIndex;
        boolean showCollisionBoxes;
        boolean intensiveTest;
        final double[] collisionOffsets = new double[3];
        final double[] renderOffsets = new double[3];

        static Draft load() {
            Draft draft = new Draft();
            draft.lifetimeSeconds = YsmRagdollConfig.lifetimeSeconds();
            draft.manualRemoval = YsmRagdollConfig.manualRemoval();
            draft.maximumRagdolls = YsmRagdollConfig.maximumRagdolls();
            draft.easyPushIndex = YsmRagdollConfig.easyPushIndex();
            draft.groundFriction = YsmRagdollConfig.groundFriction();
            draft.explosionPushIndex = YsmRagdollConfig.explosionImpactIndex();
            draft.showCollisionBoxes = YsmRagdollConfig.showCollisionBoxes();
            draft.intensiveTest = YsmRagdollConfig.intensiveTest();
            draft.collisionOffsets[0] = YsmRagdollConfig.collisionOffsetX();
            draft.collisionOffsets[1] = YsmRagdollConfig.collisionOffsetY();
            draft.collisionOffsets[2] = YsmRagdollConfig.collisionOffsetZ();
            draft.renderOffsets[0] = YsmRagdollConfig.renderOffsetX();
            draft.renderOffsets[1] = YsmRagdollConfig.renderOffsetY();
            draft.renderOffsets[2] = YsmRagdollConfig.renderOffsetZ();
            return draft;
        }

        void write() {
            YsmRagdollConfig.lifetimeSeconds(lifetimeSeconds);
            YsmRagdollConfig.manualRemoval(manualRemoval);
            YsmRagdollConfig.maximumRagdolls(maximumRagdolls);
            YsmRagdollConfig.easyPushIndex(easyPushIndex);
            YsmRagdollConfig.groundFriction(groundFriction);
            YsmRagdollConfig.explosionImpactIndex(explosionPushIndex);
            YsmRagdollConfig.showCollisionBoxes(showCollisionBoxes);
            YsmRagdollConfig.intensiveTest(intensiveTest);
            YsmRagdollConfig.collisionOffsetX(collisionOffsets[0]);
            YsmRagdollConfig.collisionOffsetY(collisionOffsets[1]);
            YsmRagdollConfig.collisionOffsetZ(collisionOffsets[2]);
            YsmRagdollConfig.renderOffsetX(renderOffsets[0]);
            YsmRagdollConfig.renderOffsetY(renderOffsets[1]);
            YsmRagdollConfig.renderOffsetZ(renderOffsets[2]);
        }
    }
}
