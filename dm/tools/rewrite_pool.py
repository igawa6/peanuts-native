# PEANUTS(upstream-candidate): worker processes for patch_nso.py's parallel phases (the x18 scan, the spin wait search) where there is no fork (Windows).
# patch_nso.py is a script whose top level is the rewrite itself, so a spawned worker cannot import it (multiprocessing's spawn would run the whole rewrite
# again as __mp_main__). Instead each worker builds the script's namespace here: its imports and its function and class definitions from the script's
# syntax tree, then the module globals the job function reads (sent by the parent, pickled), and last the top-level statements that make the globals that
# cannot be pickled (capstone handles: `md = Cs(...); md.detail = True`). The job functions only read that state, as the forked workers do (they see the
# module as it is when the pool starts), so the results are the same as in one process. patch_nso.py in_parallel; docs/reference/rewriter.md
import ast, builtins, pickle, sys, types

_NS = {}


def needed(fn, g):
    """The module globals (names in g) that fn reads, directly or through the script's functions it calls, and the code they are in"""
    seen, todo = set(), [fn.__code__]
    while todo:
        c = todo.pop()
        todo += [k for k in c.co_consts if isinstance(k, types.CodeType)]
        for n in c.co_names:
            if n in seen or n not in g: continue
            seen.add(n)
            v = g[n]
            if isinstance(v, types.FunctionType) and v.__code__.co_filename == fn.__code__.co_filename: todo.append(v.__code__)
    return seen


def state(fn, g):
    """(data, redo) for a worker: data the picklable globals fn needs (pickled once), redo the names the worker makes again by running the script's statements"""
    data, redo = {}, []
    for n in sorted(needed(fn, g)):
        v = g[n]
        if isinstance(v, (types.ModuleType, types.FunctionType, type, types.BuiltinFunctionType)): continue   # the worker's imports and definitions give these
        try: data[n] = pickle.dumps(v, pickle.HIGHEST_PROTOCOL)
        except Exception: redo.append(n)
    return pickle.dumps(data, pickle.HIGHEST_PROTOCOL), redo


def _names(node):
    """the names a top-level statement binds or changes (an assignment's targets, or the object of an attribute assigned to)"""
    out = set()
    targets = node.targets if isinstance(node, ast.Assign) else [node.target] if isinstance(node, (ast.AugAssign, ast.AnnAssign)) else []
    for t in targets:
        for x in ast.walk(t):
            if isinstance(x, ast.Name): out.add(x.id)
    return out


def init(script, path, data, redo):
    """Pool initializer: the script's namespace in this worker (see the header)"""
    sys.path[:0] = [p for p in path if p not in sys.path]
    with open(script, encoding='utf-8') as f: tree = ast.parse(f.read(), script)
    ns = {'__name__': '__dm_rewrite_worker__', '__file__': script, '__builtins__': builtins}
    run = lambda nodes: exec(compile(ast.Module(body=nodes, type_ignores=[]), script, 'exec'), ns)
    run([n for n in tree.body if isinstance(n, (ast.Import, ast.ImportFrom))])
    ns.update({k: pickle.loads(v) for k, v in pickle.loads(data).items()})
    run([n for n in tree.body if isinstance(n, (ast.FunctionDef, ast.ClassDef))])
    run([n for n in tree.body if isinstance(n, (ast.Assign, ast.AugAssign, ast.AnnAssign)) and _names(n) & set(redo)])
    _NS.clear(); _NS.update(ns)


def call_job(job):
    """one job (the script's function name, its arguments), in this worker's namespace"""
    name, args = job
    return _NS[name](*args)
