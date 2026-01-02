package com.coroutines.versioncataloghelper

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableModel
import java.util.concurrent.CompletableFuture

class LibrarySearchDialog(private val project: Project) : DialogWrapper(project) {

    private val searchField = JBTextField()
    private val resultsTable: JBTable
    private val resultsModel: DefaultTableModel

    private val nameLabel = JBLabel()
    private val versionLabel = JBLabel()
    private val groupLabel = JBLabel()
    private val descriptionPane = JEditorPane()  // Changed from JTextArea

    init {
        title = "Search Maven Repositories"

        // Results table
        resultsModel = DefaultTableModel(
            arrayOf("Name", "Latest Version", "Group"),
            0
        )
        resultsTable = JBTable(resultsModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

            selectionModel.addListSelectionListener {
                if (!it.valueIsAdjusting) {
                    updateDetails()
                }
            }
        }

        // Use JEditorPane for HTML rendering
        descriptionPane.apply {
            contentType = "text/html"
            isEditable = false
            addHyperlinkListener { e ->
                if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    // Open links in browser
                    java.awt.Desktop.getDesktop().browse(e.url.toURI())
                }
            }
        }

        init()

        // Trigger search on Enter
        searchField.addActionListener {
            performSearch()
        }
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(1100, 700)

        // Top: Search panel
        val searchPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(JBLabel("Search: "), BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)

            val searchButton = JButton("Search").apply {
                addActionListener { performSearch() }
            }
            add(searchButton, BorderLayout.EAST)
        }

        // Left: Results list
        val resultsPanel = JBScrollPane(resultsTable).apply {
            preferredSize = Dimension(450, 600)
        }

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
            add(JBScrollPane(descriptionPane).apply {
                preferredSize = Dimension(500, 400)
            })

            preferredSize = Dimension(550, 600)
        }

        // Split pane
        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            resultsPanel,
            detailsPanel
        ).apply {
            dividerLocation = 450
        }

        mainPanel.add(searchPanel, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun performSearch() {
        val keyword = searchField.text.trim()
        if (keyword.isEmpty() || keyword.length < 2) {
            return
        }

        // Clear current results
        resultsModel.rowCount = 0
        resultsModel.addRow(arrayOf("Searching...", "", ""))

        // Search in background
        CompletableFuture.supplyAsync {
            KeywordSearch.searchByKeyword(keyword)
        }.thenAccept { libraries ->
            SwingUtilities.invokeLater {
                resultsModel.rowCount = 0

                if (libraries.isEmpty()) {
                    resultsModel.addRow(arrayOf("No results found", "", ""))
                } else {
                    libraries.forEach { lib ->
                        resultsModel.addRow(
                            arrayOf(
                                lib.artifactId,
                                lib.latestVersion,
                                lib.groupId
                            )
                        )
                    }
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

        nameLabel.text = name
        versionLabel.text = version
        groupLabel.text = group

        // Show loading message
        descriptionPane.text = "<html><body><p>Loading documentation...</p></body></html>"

        // Fetch metadata in background
        CompletableFuture.supplyAsync {
            LibraryMetadataFetcher.fetchMetadata(group, name, version)
        }.thenAccept { metadata ->
            SwingUtilities.invokeLater {
                if (metadata.htmlContent != null) {
                    descriptionPane.text = metadata.htmlContent
                    descriptionPane.caretPosition = 0  // Scroll to top
                } else {
                    // Fallback to basic info
                    descriptionPane.text = """
                        <html>
                        <body style="font-family: sans-serif; padding: 10px;">
                            <h3>$group:$name</h3>
                            <p><b>Version:</b> $version</p>
                            ${metadata.description?.let { "<p><b>Description:</b> $it</p>" } ?: ""}
                            ${metadata.url?.let { "<p><b>Project URL:</b> <a href='$it'>$it</a></p>" } ?: ""}
                            <p><i>No additional documentation available.</i></p>
                        </body>
                        </html>
                    """.trimIndent()
                }
            }
        }
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

        // TODO: Actually add to libs.versions.toml
        // For now, just show what would be added
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
}