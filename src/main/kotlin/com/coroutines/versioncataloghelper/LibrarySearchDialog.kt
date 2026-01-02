package com.coroutines.versioncataloghelper

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.*
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class LibrarySearchDialog(private val project: Project) : DialogWrapper(project) {

    private val searchField = JBTextField()
    private val resultsTable: JBTable
    private val resultsModel: DefaultTableModel

    private val nameLabel = JBLabel()
    private val versionLabel = JBLabel()
    private val groupLabel = JBLabel()
    private val browser: JBCefBrowser

    private val loadingLabel = JBLabel()

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var searchJob: Job? = null

    private var animationTimer: Timer? = null
    private var animationFrame = 0

    private var currentVendor: String? = null

    init {
        title = "Search Maven Repositories"

        // Results table
        resultsModel = object : DefaultTableModel(
            arrayOf("Name", "Latest Version", "Group"),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        resultsTable = JBTable(resultsModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

            // Custom renderer for group headers
            setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable?,
                    value: Any?,
                    isSelected: Boolean,
                    hasFocus: Boolean,
                    row: Int,
                    column: Int
                ): Component {
                    val cell = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

                    // Check if this is a header row
                    val name = resultsModel.getValueAt(row, 0) as? String ?: ""
                    if (name.startsWith("━━━")) {
                        font = font.deriveFont(Font.BOLD, 13f)
                        background = table?.background?.darker()
                        foreground = table?.foreground
                    } else {
                        font = font.deriveFont(Font.PLAIN, 12f)
                        background = if (isSelected) table?.selectionBackground else table?.background
                        foreground = if (isSelected) table?.selectionForeground else table?.foreground
                    }

                    return cell
                }
            })

            selectionModel.addListSelectionListener {
                if (!it.valueIsAdjusting) {
                    updateDetails()
                }
            }
        }

        browser = JBCefBrowser()
        browser.loadHTML(getWelcomeHtml())

        init()

        // Auto-search on typing (with debounce)
        searchField.document.addDocumentListener(object : DocumentListener {
            private var debounceTimer: Timer? = null

            override fun insertUpdate(e: DocumentEvent?) = scheduleSearch()
            override fun removeUpdate(e: DocumentEvent?) = scheduleSearch()
            override fun changedUpdate(e: DocumentEvent?) = scheduleSearch()

            private fun scheduleSearch() {
                debounceTimer?.stop()
                debounceTimer = Timer(300) {
                    SwingUtilities.invokeLater {
                        performSearch()
                    }
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        })
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(1200, 750)

        // Top: Search panel
        val searchPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(JBLabel("Search (min 5 chars): "), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }

        // Left: Results list with loading indicator at bottom
        val leftPanel = JPanel(BorderLayout())
        leftPanel.add(JBScrollPane(resultsTable), BorderLayout.CENTER)

        loadingLabel.horizontalAlignment = SwingConstants.CENTER
        loadingLabel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        leftPanel.add(loadingLabel, BorderLayout.SOUTH)
        leftPanel.preferredSize = Dimension(450, 650)

        // Right: Details panel
        val detailsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

            add(JBLabel("Library Details").apply {
                font = font.deriveFont(16f)
            })
            add(Box.createVerticalStrut(10))

            add(JBLabel("Name:"))
            add(nameLabel)
            add(Box.createVerticalStrut(10))

            add(JBLabel("Latest Version:"))
            add(versionLabel)
            add(Box.createVerticalStrut(10))

            add(JBLabel("Group ID:"))
            add(groupLabel)
            add(Box.createVerticalStrut(10))

            add(JBLabel("Documentation:"))
            add(browser.component.apply {
                preferredSize = Dimension(650, 450)
            })

            preferredSize = Dimension(700, 650)
        }

        // Split pane
        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            leftPanel,
            detailsPanel
        ).apply {
            dividerLocation = 450
        }

        mainPanel.add(searchPanel, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun startLoadingAnimation() {
        stopLoadingAnimation()
        animationFrame = 0
        loadingLabel.text = "🔍 Searching..."

        animationTimer = Timer(200) {
            val dots = ".".repeat((animationFrame % 4))
            loadingLabel.text = "🔍 Searching$dots"
            animationFrame++
        }.apply {
            start()
        }
    }

    private fun stopLoadingAnimation() {
        animationTimer?.stop()
        animationTimer = null
        loadingLabel.text = ""
    }

    private fun performSearch() {
        val keyword = searchField.text.trim()
        if (keyword.length < 5) {
            if (keyword.isEmpty()) {
                resultsModel.rowCount = 0
                stopLoadingAnimation()
            }
            return
        }

        // Cancel previous search
        searchJob?.cancel()

        // Clear results and start animation
        resultsModel.rowCount = 0
        currentVendor = null
        startLoadingAnimation()

        // Start new search with streaming results
        searchJob = scope.launch(Dispatchers.IO) {
            try {
                KeywordSearch.searchByKeywordFlow(keyword)
                    .catch { e ->
                        println("=== Flow error: ${e.message} ===")
                        e.printStackTrace()
                    }
                    .collect { result ->
                        withContext(Dispatchers.Main) {
                            // Add vendor header if vendor changed
                            if (currentVendor != result.vendor) {
                                currentVendor = result.vendor
                                resultsModel.addRow(
                                    arrayOf("━━━ $currentVendor ━━━", "", "")
                                )
                            }

                            // Add the library
                            resultsModel.addRow(
                                arrayOf(
                                    result.library.artifactId,
                                    result.library.latestVersion,
                                    result.library.groupId
                                )
                            )

                            println("=== Added: ${result.library.groupId}:${result.library.artifactId} ===")
                        }
                    }

                // Search complete
                withContext(Dispatchers.Main) {
                    stopLoadingAnimation()

                    if (resultsModel.rowCount == 0) {
                        resultsModel.addRow(arrayOf("No results found", "", ""))
                    }
                }

            } catch (e: CancellationException) {
                println("=== Search cancelled ===")
                withContext(Dispatchers.Main) {
                    stopLoadingAnimation()
                }
            } catch (e: Exception) {
                println("=== Search error: ${e.message} ===")
                e.printStackTrace()

                withContext(Dispatchers.Main) {
                    stopLoadingAnimation()
                    resultsModel.rowCount = 0
                    resultsModel.addRow(arrayOf("Error: ${e.message}", "", ""))
                }
            }
        }
    }

    private fun updateDetails() {
        val selectedRow = resultsTable.selectedRow
        if (selectedRow < 0) return

        val name = resultsModel.getValueAt(selectedRow, 0) as String
        val version = resultsModel.getValueAt(selectedRow, 1) as String
        val group = resultsModel.getValueAt(selectedRow, 2) as String

        // Skip special rows (headers, no results, errors)
        if (name.startsWith("━━━") || name == "No results found" || name.startsWith("Error:")) {
            return
        }

        nameLabel.text = name
        versionLabel.text = version
        groupLabel.text = group

        browser.loadHTML(getLoadingHtml())

        scope.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    LibraryMetadataFetcher.fetchMetadata(group, name, version)
                }

                withContext(Dispatchers.Main) {
                    if (metadata.htmlContent != null) {
                        browser.loadHTML(metadata.htmlContent)
                    } else if (metadata.url != null) {
                        browser.loadURL(metadata.url)
                    } else {
                        browser.loadHTML(getBasicInfoHtml(group, name, version, metadata.description))
                    }
                }
            } catch (e: Exception) {
                println("=== Error loading metadata: ${e.message} ===")
                e.printStackTrace()
            }
        }
    }

    private fun getWelcomeHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
                        padding: 40px;
                        background: #ffffff;
                        color: #333;
                    }
                    .welcome {
                        background: #ffffff;
                        padding: 32px;
                        border-radius: 8px;
                        border: 2px solid #e8eaed;
                    }
                    h2 {
                        color: #1a73e8;
                        margin-top: 0;
                        font-weight: 500;
                    }
                    p {
                        color: #5f6368;
                        line-height: 1.6;
                    }
                </style>
            </head>
            <body>
                <div class="welcome">
                    <h2>🔍 Maven Repository Search</h2>
                    <p>Search for libraries and view their documentation.</p>
                    <p>Type at least 5 characters to start searching.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getLoadingHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
                        padding: 40px;
                        background: #ffffff;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 400px;
                    }
                    .loader {
                        text-align: center;
                    }
                    .spinner {
                        border: 4px solid #e8eaed;
                        border-top: 4px solid #1a73e8;
                        border-radius: 50%;
                        width: 40px;
                        height: 40px;
                        animation: spin 1s linear infinite;
                        margin: 0 auto 20px;
                    }
                    @keyframes spin {
                        0% { transform: rotate(0deg); }
                        100% { transform: rotate(360deg); }
                    }
                    p {
                        color: #5f6368;
                    }
                </style>
            </head>
            <body>
                <div class="loader">
                    <div class="spinner"></div>
                    <p>Loading documentation...</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getBasicInfoHtml(group: String, artifact: String, version: String, description: String?): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
                        padding: 24px;
                        background: #ffffff;
                        color: #333;
                    }
                    .card {
                        background: #ffffff;
                        padding: 24px;
                        border-radius: 8px;
                        border: 1px solid #e8eaed;
                    }
                    h2 {
                        color: #1a73e8;
                        margin-top: 0;
                        font-weight: 500;
                    }
                    .info-row {
                        margin: 16px 0;
                    }
                    .label {
                        font-weight: 600;
                        color: #5f6368;
                        margin-bottom: 4px;
                    }
                    .value {
                        font-family: 'Monaco', 'Courier New', monospace;
                        background: #f8f9fa;
                        padding: 8px 12px;
                        border-radius: 4px;
                        display: inline-block;
                        margin-top: 4px;
                        color: #202124;
                        border: 1px solid #e8eaed;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>$group:$artifact</h2>
                    <div class="info-row">
                        <div class="label">Version:</div>
                        <div class="value">$version</div>
                    </div>
                    ${description?.let { """
                        <div class="info-row">
                            <div class="label">Description:</div>
                            <p>$it</p>
                        </div>
                    """ } ?: ""}
                    <div class="info-row">
                        <p style="color: #5f6368; font-style: italic;">No additional documentation available.</p>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            object : DialogWrapperAction("Add to Catalog") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    addToCatalog()
                }
            },
            cancelAction
        )
    }

    private fun addToCatalog() {
        val selectedRow = resultsTable.selectedRow
        if (selectedRow < 0) return

        val artifactId = resultsModel.getValueAt(selectedRow, 0) as String
        val version = resultsModel.getValueAt(selectedRow, 1) as String
        val groupId = resultsModel.getValueAt(selectedRow, 2) as String

        // Skip special rows
        if (artifactId.startsWith("━━━") || artifactId == "No results found" || artifactId.startsWith("Error:")) {
            return
        }

        val message = """
            Add to libs.versions.toml:
            
            [versions]
            $artifactId = "$version"
            
            [libraries]
            $artifactId = { group = "$groupId", name = "$artifactId", version.ref = "$artifactId" }
        """.trimIndent()

        JOptionPane.showMessageDialog(
            contentPane,
            message,
            "Add to Catalog",
            JOptionPane.INFORMATION_MESSAGE
        )

        close(OK_EXIT_CODE)
    }

    override fun dispose() {
        searchJob?.cancel()
        stopLoadingAnimation()
        scope.coroutineContext.cancelChildren()
        browser.dispose()
        super.dispose()
    }
}