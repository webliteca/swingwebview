/*
 * MIT License
 *
 * Demonstrates embedding a native WebView as a heavyweight Swing component,
 * and exercises the trickier mixing scenarios with surrounding Swing
 * widgets: a JComboBox whose drop-down extends over the WebView area, and
 * a JTabbedPane where the WebView is one of several tabs.
 */
package ca.weblite.webview.demos;

import ca.weblite.webview.swing.WebViewHeavyweightComponent;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ToolTipManager;

public class WebViewHeavyweightDemo {

    public static void main(String[] args) {
        // Heavyweight popups so JComboBox dropdowns, tooltips, and menus
        // render as real NSWindows that sit above the WKWebView heavyweight
        // peer.  Without this their lightweight Swing form would be painted
        // behind the WebView and effectively invisible.
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        EventQueue.invokeLater(WebViewHeavyweightDemo::run);
    }

    private static void run() {
        JFrame frame = new JFrame("WebView Heavyweight Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // ----- WebView and URL bar -----
        WebViewHeavyweightComponent wv = new WebViewHeavyweightComponent();
        wv.setUrl("https://example.com");
        wv.setPreferredSize(new Dimension(900, 600));

        JTextField urlField = new JTextField("https://example.com", 50);
        urlField.setToolTipText("Press Enter or click Go to navigate.");
        JButton go = new JButton("Go");
        go.addActionListener(e -> wv.setUrl(urlField.getText().trim()));
        urlField.addActionListener(e -> wv.setUrl(urlField.getText().trim()));

        // JComboBox in the toolbar -- drop-down should open over the
        // WebView area when "Browser" tab is selected.  This is the
        // canonical "does heavyweight popup work?" test.
        JComboBox<String> bookmark = new JComboBox<>(new String[] {
            "Bookmarks ...",
            "https://example.com",
            "https://en.wikipedia.org",
            "https://news.ycombinator.com",
            "https://www.google.com",
            "https://www.openjdk.org",
            "https://www.apple.com",
        });
        bookmark.setToolTipText("Open the dropdown to verify it appears above the WebView.");
        bookmark.addActionListener(e -> {
            int i = bookmark.getSelectedIndex();
            if (i <= 0) return;
            String u = (String) bookmark.getSelectedItem();
            urlField.setText(u);
            wv.setUrl(u);
        });

        JCheckBox lightweightToggle = new JCheckBox("Lightweight popups");
        lightweightToggle.setToolTipText(
            "Toggle to flip popup style.  When checked, dropdowns try to " +
            "use lightweight Swing rendering -- which on this WebView " +
            "renders BEHIND the heavyweight peer.  Restart required for " +
            "the change to apply to already-created components.");
        lightweightToggle.addActionListener(e -> {
            JPopupMenu.setDefaultLightWeightPopupEnabled(
                lightweightToggle.isSelected());
            ToolTipManager.sharedInstance().setLightWeightPopupEnabled(
                lightweightToggle.isSelected());
        });

        JPanel toolbar = new JPanel(new BorderLayout(8, 4));
        JPanel toolbarWest = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolbarWest.add(new JLabel("URL:"));
        toolbarWest.add(urlField);
        toolbarWest.add(go);
        JPanel toolbarEast = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        toolbarEast.add(bookmark);
        toolbarEast.add(lightweightToggle);
        toolbar.add(toolbarWest, BorderLayout.CENTER);
        toolbar.add(toolbarEast, BorderLayout.EAST);

        // ----- Tabs -----
        JTabbedPane tabs = new JTabbedPane();

        JPanel browserTab = new JPanel(new BorderLayout());
        browserTab.add(wv, BorderLayout.CENTER);
        tabs.addTab("Browser", browserTab);

        JPanel swingTab = new JPanel(new BorderLayout(8, 8));
        JTextArea explainer = new JTextArea(
            "This tab is pure Swing.\n\n" +
            "Switch back to the 'Browser' tab to verify the embedded\n" +
            "WebView re-appears (a HierarchyListener on the heavyweight\n" +
            "Canvas calls setHidden:YES on the WKWebView when this tab\n" +
            "becomes active, and reverses it on the way back).");
        explainer.setEditable(false);
        explainer.setMargin(new java.awt.Insets(12, 12, 12, 12));
        swingTab.add(explainer, BorderLayout.NORTH);
        DefaultListModel<String> items = new DefaultListModel<>();
        for (int i = 1; i <= 30; i++) {
            items.addElement("Swing list item " + i);
        }
        swingTab.add(new JScrollPane(new JList<>(items)), BorderLayout.CENTER);
        tabs.addTab("Swing only", swingTab);

        // ----- Frame -----
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(toolbar, BorderLayout.NORTH);
        frame.getContentPane().add(tabs, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
