package io.causvane.gui;

import io.causvane.Main;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyEvent;

/** "About CausVane" and "Keyboard Shortcuts": small windows opened from the menus. */
final class AboutDialog {

    private AboutDialog() {}

    static void showAbout(JFrame owner) {
        JDialog dialog = new JDialog(owner, "About CausVane", true);
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(24, 32, 12, 32));
        JLabel icon = new JLabel(new ImageIcon(AppIcon.render(160, 0).getScaledInstance(80, 80, java.awt.Image.SCALE_SMOOTH)));
        JLabel name = new JLabel(Main.APP_NAME);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 22f));
        JLabel version = new JLabel("Version " + Main.VERSION);
        version.setForeground(Theme.DIM);
        JLabel tagline = new JLabel("<html><div style='text-align: center; width: 260px'>Reads the log of any application, finds every "
                + "problem, explains it in plain words and points to where it started.</div></html>");
        JLabel open = new JLabel("Free and open source.");
        open.setForeground(Theme.DIM);
        for (JComponent c : new JComponent[] {icon, name, version, tagline, open}) {
            c.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        body.add(icon);
        body.add(Box.createVerticalStrut(12));
        body.add(name);
        body.add(Box.createVerticalStrut(2));
        body.add(version);
        body.add(Box.createVerticalStrut(14));
        body.add(tagline);
        body.add(Box.createVerticalStrut(10));
        body.add(open);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 16, 18, 16));
        JButton site = new JButton("Visit the Website");
        site.addActionListener(e -> AppMenuBar.browse(Main.REPOSITORY));
        JButton updates = new JButton("Check for Updates");
        updates.addActionListener(e -> AppMenuBar.browse(Main.REPOSITORY + "/releases/latest"));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        buttons.add(site);
        buttons.add(updates);
        buttons.add(close);
        show(dialog, body, buttons, close);
    }

    static void showShortcuts(JFrame owner) {
        String key = AppMenuBar.mac() ? "⌘" : "Ctrl+";
        String shift = AppMenuBar.mac() ? "⇧" : "Shift+";
        String[][] rows = {
                {key + "O", "Open a log file"},
                {shift + key + "V", "Paste log text from the clipboard"},
                {key + "S", "Save a report as Markdown"},
                {shift + key + "S", "Save a report as JSON"},
                {shift + key + "C", "Copy a summary for a ticket"},
                {key + "F", "Find a problem"},
                {key + "K", "Quick actions: find any problem or action"},
                {"Esc", "Back to the overview of the whole log"},
                {key + "1 … " + key + "4", "Show all problems, fatal, errors or warnings only"},
                {shift + key + "T", "Switch between the light and dark theme"},
                {shift + key + "M", "Meeting mode: the log as full-screen slides (arrow keys move, Esc leaves)"},
                {key + "/", "This list"},
        };
        StringBuilder html = new StringBuilder("<html><body style='font-size: 12px'><table cellspacing='0' cellpadding='4'>");
        for (String[] row : rows) {
            html.append("<tr><td><b>").append(row[0]).append("</b></td><td>&nbsp;&nbsp;</td><td>").append(row[1]).append("</td></tr>");
        }
        html.append("</table></body></html>");
        JEditorPane list = new JEditorPane("text/html", html.toString());
        list.setEditable(false);
        list.setOpaque(false);
        list.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        JDialog dialog = new JDialog(owner, "Keyboard Shortcuts", true);
        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(BorderFactory.createEmptyBorder(18, 22, 6, 22));
        body.add(list, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 16, 14, 16));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        buttons.add(close);
        show(dialog, body, buttons, close);
    }

    private static void show(JDialog dialog, JComponent body, JComponent buttons, JButton close) {
        JPanel content = new JPanel(new BorderLayout());
        content.add(body, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setResizable(false);
        dialog.pack();
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }
}
