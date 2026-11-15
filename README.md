# Spreadsheet Pro (Java Swing)

Excel-style spreadsheet in pure Java (JDK 8+, no external libraries).

## Download and run
Get the latest files from the **Releases** page of this repository.

| You are on | Download | How to run |
|---|---|---|
| Windows | `SpreadsheetPro-Windows.zip` | unzip, open the `Spreadsheet Pro` folder, double-click `Spreadsheet Pro.exe` |
| macOS | `SpreadsheetPro-macOS.zip` | unzip, open `Spreadsheet Pro.app` (first time: right-click > Open) |
| Linux | `SpreadsheetPro-Linux.tar.gz` | extract, run `bin/Spreadsheet\ Pro` |
| Any (Java 17+ installed) | `SpreadsheetPro.jar` | double-click it, or `java -jar SpreadsheetPro.jar` |

The Windows, macOS and Linux downloads already contain Java, so nothing else has to be installed.
They are not code-signed, so Windows SmartScreen or macOS Gatekeeper may warn you the first time.

**For maintainers:** publishing a GitHub Release runs `.github/workflows/release.yml`, which builds all four files and attaches them to the release. `make-jar.sh` / `make-jar.bat` build the jar by hand.

## Compile and run

Linux / macOS:
    chmod +x build.sh && ./build.sh        # compiles and starts the app

Windows:
    build.bat

Manual:
    mkdir out
    javac -encoding UTF-8 -d out *.java
    java -cp out SpreadsheetApp

## Architecture

| File                        | Layer                                                       |
|-----------------------------|-------------------------------------------------------------|
| SpreadsheetApp.java         | UI: ribbon, formula bar, tabs, status bar, CSV import/export |
| SpreadsheetModel.java       | Data model (AbstractTableModel): raw / formula / style maps  |
| FormulaEngine.java          | Expression-tree formula parser, 64 functions, error codes    |
| Icons.java                  | Vector icons drawn with Java2D (no image files)              |
| DrawShape.java / DrawingLayer.java | Pen, highlighter, shapes and eraser painted over the sheet |
| FunctionCatalog.java        | Names, syntax and examples of every function                 |
| InsertFunctionDialog.java   | fx function picker (search + categories)                     |
| SpreadsheetCellRenderer.java| Cell rendering (bold, italic, alignment, colours, borders)   |
| CellStyle.java              | Per-cell formatting record                                   |
| InsertDialog.java           | Insert date / random data / sales table / chart placeholder  |
| MacroIDEDialog.java         | VBA editor + console                                         |
| MacroEngine.java            | VBA-subset interpreter                                       |
| WorkbookIO.java             | File layer: save / load .spx workbooks, optional encryption  |
| SheetProtection.java        | Sheet protection state + salted PBKDF2 password hash         |
| CellFormats.java            | Number formats: General, Number, Comma, Currency, Percent, Scientific |
| CondRule.java               | Conditional-formatting rules (greater, less, between, text, duplicates) |
| AutoFilter.java / FilterDialog.java | Filter state and the value-picker drop-down            |
| FormulaRefs.java            | Rewrites formulas when rows/columns are inserted, deleted or sorted |
| PivotBuilder.java / PivotDialog.java | PivotTable, column summary, calculated columns         |
| AutoSaveManager.java        | AutoSave timer, recovery copies, crash recovery              |
| XlsxIO.java                 | Excel (.xlsx) import and export (pure JDK)                   |
| ChartSpec / ChartData / ChartPainter / ChartLayer | Interactive charts (drawing, live data, hover, drag) |
| FillSeries.java / FillHandle.java | Fill handle: series, text, formulas, undo              |
| ValidationRule.java                | Data validation rules (list, whole, decimal, length)   |
| PageSetup.java                     | Paper, margins, scale, header/footer, print area       |
| PrintLayout.java                   | Cuts a sheet into pages; paints preview and printout   |
| PageSetupDialog.java / PrintPreviewDialog.java | Page Setup dialog and Print Preview window |
| VersionStore.java / VersionHistoryDialog.java | Saved copies of a workbook and the history window |
| CommandPalette.java / ShortcutsDialog.java | Ctrl+Shift+P command search; searchable shortcut list |
| SplashWindow.java                  | Start-up animation                                     |
| GoalSeek.java                      | What-If Goal Seek (secant + bisection)                 |
| Sparkline.java / SparklinePainter.java | Tiny in-cell line / column / win-loss charts       |
| SheetImage.java             | A picture on a sheet (PNG bytes + position + size)           |
| ImageLayer.java             | Draws pictures over the grid; select / drag / resize         |

## Ribbon tabs
Home, Insert, Draw, Page Layout, Data, Power Pivot, Developer, Review, View, Help.

- **Home**: font family + size, bold/italic, fill and text colour; Number group (format list, %, comma, more/fewer decimals; typing `25%` stores 0.25); Styles (Cell Styles gallery, Conditional formatting); Cells (insert/delete rows and columns, formulas are adjusted); Editing (AutoSum, Function, Sort & Filter).
- **Data**: Sort A-Z / Z-A (header row stays on top), Filter (click the arrow on a header cell; search and tick values), Clear, Remove Duplicates, Text to Columns, Import/Export CSV.
- **Power Pivot**: PivotTable (rows, optional columns, Sum/Count/Average/Min/Max, grand totals) on a new sheet; Summary statistics; Calculated Column such as `[Qty]*[Price]`.
- **Page Layout**: Page Setup, Orientation, Fit Width, Set / Clear Print Area, print Gridlines / Headings / Repeat Row 1, Print Preview, Print.
- **Review**: Read-Only mode, Version History, Save Version, Recover Unsaved.
- **Developer**: VBA macros, Show Formulas, Inspect Cell, Workbook Info.
- **Help**: formula reference, keyboard shortcuts, About, AutoSave settings.

## Excel files (.xlsx)
File > Open (choose *.xlsx) or Data > Open Excel reads an Excel workbook; File > Export to Excel or Data > Export Excel writes one. Carried over: sheets, values, formulas (with their last result), fonts, colours, number formats, alignment, column widths; dates become text (yyyy-MM-dd). Not carried over: pictures, drawings, charts, conditional formatting, filters, protection. Formulas using things this app lacks (other-sheet references, newer functions) keep their last Excel value. Use .spx to keep everything.

## Charts
Insert > Chart: Column, Bar, Line, Area, Pie, Scatter from the selected cells (first row = series names, first column = labels). Hover for exact values, click a legend entry to hide a series, click the chart to select it (source cells are outlined), drag to move, drag the corner to resize, double-click or Edit Chart to change type/title/range, right-click for a menu, Delete to remove. Charts update live as cells change and are saved in .spx.

## Fill handle
Drag the small square at the corner of the selection down, up, left or right: 1, 2 continues 3, 4 ...; Item 1, Mon, Jan continue; formulas copy with relative references ($A$1 stays); other text repeats. Double-click the square to fill down as far as the neighbouring column. Ctrl+Z undoes the last fill.

## Data tools (Data tab)
- **Validation**: select cells, pick Allow (List, Whole number, Decimal, Text length), the condition, an optional input message and a Stop / Warning / Information alert. Lists can be typed (`Yes,No,Maybe`) or a range (`=$A$1:$A$5`) and show a dropdown arrow in the cell. Bad entries are refused when you type them. **Circle Invalid** rings cells that already break a rule; **Clear Rules** removes them. Rules are saved in .spx and in .xlsx.
- **Custom Sort**: sort by several columns, each ascending or descending, with a "my data has headers" option.
- **Goal Seek**: choose a formula cell, the value you want and the number cell to change; the app finds the input and asks whether to keep it.
- **Sparklines** (Insert tab): tiny line, column or win/loss charts drawn inside cells from a row or column of numbers. They are saved in .spx; .xlsx files note that sparklines are not exported.
- **Sheet Size**: grow a sheet to 100,000, 500,000 or 1,048,576 rows. Only used cells take memory, and row numbers are made on demand. Tested with 200,000 rows saved and loaded as .spx and .xlsx.

## Printing (Page Layout tab, File menu)
- **Print Preview** shows the pages exactly as they will print (Page Up / Page Down to flip, zoom, Print... and Page Setup... buttons). **Print...** (Ctrl+P) goes straight to the printer dialog.
- **Page Setup**: Letter / Legal / A4 / A3 / A5, portrait or landscape, margins, scale 10-400% or *fit all columns on one page wide* / *whole area on one page*, center on page, page order, repeat top rows, gridlines and row/column headings.
- **Header and footer** have left, center and right parts with codes: `&P` page, `&N` pages, `&D` date, `&T` time, `&F` file name, `&A` sheet name.
- **Print area**: select cells and press *Set Print Area*; only those cells print. Pictures, charts and sparklines print too, and rows hidden by a filter are left out.
- Page setup is saved per sheet inside the .spx file. (It is not part of the .xlsx export yet.)

## Safety (Review tab, File menu)
- **Version History**: every save keeps a copy (the newest 30 plain copies, plus any you name with *Save Version*). Open the history to restore one; what you had open is kept as a version first, so a restore can always be undone. Copies live in `~/.spreadsheet-pro/versions`.
- **Recover Unsaved Workbooks**: lists the copies AutoSave kept after a crash so you can open or delete them any time (they are also offered at start-up).
- **Read-Only Mode**: blocks every edit (typing, paste, sort, pictures, drawing, sheets) until you turn it off; Save becomes Save As so the original stays untouched. Workbooks whose file is write-protected open read-only automatically.

## Command palette and shortcuts
- **Ctrl+Shift+P** opens a search box over every menu command and ribbon button (and "Go to sheet"). Type a few letters, Up / Down, Enter.
- **Ctrl+/** (Help > Keyboard Shortcuts) opens a searchable list of every shortcut.

## Start-up animation
A short splash screen builds a little sheet cell by cell while the real window loads. Click it to skip, or start with `--no-splash` (or `-Dspx.nosplash=true`).

## AutoSave
The AutoSave switch (top right of the ribbon) saves unsaved work every N seconds (default 60). Mode 1 (default) writes a separate recovery copy in `~/.spreadsheet-pro/autosave` and never touches your file; mode 2 saves into the open file. Encrypted workbooks stay encrypted. After a crash the app offers to recover the copy on the next start. Settings: File > AutoSave Settings or Help tab.

## Formulas
Start with `=`. Operators `+ - * / ^ %` and parentheses; references `A1`; ranges `A1:A10`.
64 functions. Click **fx** (or Insert > Function, Shift+F3) to search and insert them; Help > Formula Reference lists all.

- Math & Statistics: SUM AVERAGE MIN MAX COUNT COUNTA COUNTBLANK PRODUCT MEDIAN STDEV VAR LARGE SMALL ABS SQRT ROUND ROUNDUP ROUNDDOWN INT MOD POWER EXP LN LOG LOG10 SIGN PI RAND RANDBETWEEN
- Logical: IF AND OR NOT IFERROR ISNUMBER ISTEXT ISBLANK ISERROR COUNTIF SUMIF AVERAGEIF
- Text: CONCATENATE CONCAT LEN UPPER LOWER PROPER TRIM LEFT RIGHT MID SUBSTITUTE FIND REPT VALUE (and the & operator)
- Lookup: VLOOKUP MATCH INDEX
- Date: TODAY NOW YEAR MONTH DAY DAYS

Also comparison operators (= <> < > <= >=), text in "quotes", and TRUE/FALSE values. IF only evaluates the branch it picks, so `=IF(A1=0,0,10/A1)` is safe. Extra error: `#N/A` (lookup not found).

## Zoom and column width

- Zoom 25%-300%: View menu, status-bar slider / - / + buttons, Ctrl+mouse wheel, Ctrl +/-/0. Each sheet remembers its own zoom.
- Resize a column by dragging the right edge of its header; double-click the edge to fit the content. View > Reset Column Widths restores the default.
- Zoom, column widths and pictures are saved in the .spx file (older files still open).
Errors: #DIV/0!, #SYNTAX!, #NAME?, #ERROR! (bad reference, text used as number, circular reference).

## Macro example
    Sub Demo()
        For i = 1 To 10
            Cells(i, 1).Value = i * i
            If i Mod 2 = 0 Then Cells(i, 1).Interior.Color = RGB(198, 229, 209)
        Next i
        Range("A11").Formula = "=SUM(A1:A10)"
        Range("A11").Font.Bold = True
    End Sub
Supported: Dim, For/Next (Step, Exit For), If/ElseIf/Else/End If (and single-line If),
Cells(r,c) / Range("A1:B3") with .Value .Formula .Font.Bold/Italic/Size/Color .Interior.Color
.HorizontalAlignment, ClearContents, Clear, MsgBox, Debug.Print, and Int Abs Sqr Rnd Round
Len UCase LCase Str Val Mid RGB.

## Shortcuts
Ctrl+B bold, Ctrl+I italic, Ctrl+L/E/R align, Delete clear, F2 / double-click edit,
Ctrl+N new sheet. Double-click a sheet tab to rename it; the x closes it.

## Files (File menu)
- New Workbook (Ctrl+N), Open... (Ctrl+O), Save (Ctrl+S), Save As... (Ctrl+Shift+S).
- Workbooks are saved as `.spx` and keep all sheets, values, formulas, formatting, pictures
  and sheet protection. The title bar shows `*` for unsaved changes and you are asked
  before anything is discarded.
- Set Open Password...: encrypts the saved file (AES-256-GCM, key from PBKDF2). The password is
  asked when the file is opened. A forgotten password cannot be recovered.
- Import CSV / Export CSV still work as before.

## Pictures
- Insert > Picture from File... (or the Picture button): PNG, JPG, GIF, BMP.
- Insert > Picture from Clipboard: pastes an image copied from another program.
- Click a picture to select it, drag to move, drag the small square in its corner to resize
  (hold Shift to stretch freely), press Delete to remove it.

## Sheet protection (Review menu or the Protect Sheet button)
- Protect with a password, or leave it blank to protect without one.
- A protected sheet cannot be edited, formatted, cleared, auto-summed, filled by a macro or
  have its pictures changed or deleted, and cannot be deleted. A padlock shows on its tab.
- Unprotect Sheet asks for the password. Passwords are never stored, only a salted hash.
- Sheet protection stops changes inside the app (like Excel). To keep a file's contents
  private use Set Open Password, which encrypts it.

## Ribbon, icons and interactivity

- The ribbon has tabs: **Home**, **Insert**, **Draw** and **View**. Every button has an icon and a tooltip with its shortcut.
- Bold / Italic / alignment buttons light up to match the selected cell.
- The cell, column header and row header under the mouse get a faint highlight.
- Right-click a cell for a menu (insert function, clear, bold/italic, alignment, fill colour, fit column, insert picture).
- Menus have icons too. The window and task-bar icon is drawn in code.

## Drawing and painting (Draw tab)

Tools: Pen, Highlighter, Line, Rectangle, Ellipse, Eraser (the arrow, "Select", goes back to normal cell selection; switching to another ribbon tab also turns drawing off).

- Pick one of 8 quick colours or any colour with **More**; the slider sets the brush thickness; **Fill shapes** fills rectangles and ellipses.
- Hold **Shift** for lines at 45 degree steps and perfect squares / circles.
- The eraser removes the whole stroke or shape you touch.
- **Undo** (Ctrl+Z), **Redo** (Ctrl+Y) and **Clear All** (also undoable).
- Drawings follow the zoom level, are saved in the .spx file (format v3; older files still open) and are locked while the sheet is protected.
