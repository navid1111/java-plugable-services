"""Lab 1 - Static Analysis: generate the AST of a Java source file using javalang."""
import sys
import javalang
import javalang.tree

def label(node):
    """Human-readable label for an AST node: type + identifying attribute."""
    name = type(node).__name__
    for attr in ("name", "member", "value", "operator", "qualifier"):
        v = getattr(node, attr, None)
        if isinstance(v, str) and v:
            return f"{name} ({attr}={v!r})"
    return name

def walk(node, indent=0, out=None):
    pad = "    " * indent
    if isinstance(node, javalang.tree.Node):
        out.append(f"{pad}{label(node)}")
        for child in node.children:
            walk(child, indent + 1, out)
    elif isinstance(node, (list, tuple, set)):
        for item in node:
            walk(item, indent, out)
    # plain strings/None already shown via label(); skip

path = sys.argv[1]
src = open(path).read()
tree = javalang.parse.parse(src)

lines = []
walk(tree, 0, lines)
print(f"AST of {path}")
print(f"total nodes shown: {len(lines)}")
print("=" * 70)
print("\n".join(lines))
