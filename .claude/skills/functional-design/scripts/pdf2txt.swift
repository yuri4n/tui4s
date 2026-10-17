// Usage: swift pdf2txt.swift <in.pdf> <out.txt>
// Writes the text of each page, preceded by a "=== PAGE n ===" marker.
import Foundation
import PDFKit

let args = CommandLine.arguments
guard args.count == 3 else {
    FileHandle.standardError.write("usage: pdf2txt <in.pdf> <out.txt>\n".data(using: .utf8)!)
    exit(2)
}
guard let doc = PDFDocument(url: URL(fileURLWithPath: args[1])) else {
    FileHandle.standardError.write("cannot open \(args[1])\n".data(using: .utf8)!)
    exit(1)
}
var out = ""
for i in 0..<doc.pageCount {
    out += "\n=== PAGE \(i + 1) ===\n"
    out += doc.page(at: i)?.string ?? ""
}
try out.write(toFile: args[2], atomically: true, encoding: .utf8)
print("\(doc.pageCount) pages -> \(args[2])")
