"""Immutable local package storage and compatible dependency resolution."""
import contextlib
import datetime
import json
import os
import shutil
import tempfile
from pathlib import Path

from manifest import INT_MAX, MarketError, inspect_package, plugin_id, positive_int


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def atomic_json(path, value):
    descriptor, temporary = tempfile.mkstemp(prefix=".pending-", dir=str(path.parent))
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class Client:
    def __init__(self, value):
        if not isinstance(value, dict):
            raise MarketError("Client compatibility must be an object")
        self.host_api = positive_int(value.get("hostApiVersion"), "hostApiVersion")
        self.sdk = positive_int(value.get("sdk"), "sdk")
        self.runtime = value.get("runtimeVersion")
        self.abis = value.get("abis")
        if not isinstance(self.runtime, str) or not self.runtime:
            raise MarketError("runtimeVersion is required")
        if (not isinstance(self.abis, list) or not self.abis
                or any(not isinstance(abi, str) or not abi for abi in self.abis)):
            raise MarketError("abis must contain device ABI names")

    def compatible(self, manifest):
        return (manifest["hostApiVersion"] == self.host_api
                and manifest["runtimeVersion"] == self.runtime
                and manifest["minSdk"] <= self.sdk
                and (not manifest["abis"] or bool(set(self.abis) & set(manifest["abis"]))))


class PackageStore:
    def __init__(self, root, verifier=None, required=()):
        self.root = Path(root).resolve()
        self.packages = self.root / "packages"
        self.packages.mkdir(parents=True, exist_ok=True)
        self.verifier = verifier
        self.required = tuple(plugin_id(value) for value in required)

    @contextlib.contextmanager
    def writer_lock(self):
        path = self.root / ".writer.lock"
        try:
            descriptor = os.open(str(path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError as error:
            raise MarketError("Publisher is busy; inspect a stale .writer.lock after a crashed writer",
                              "publisher_busy", 409) from error
        try:
            with os.fdopen(descriptor, "w") as stream:
                stream.write("pid=%s time=%s\n" % (os.getpid(), utc_now()))
            yield
        finally:
            path.unlink()

    def publish(self, source):
        if self.verifier is None:
            raise MarketError("Publication requires a trusted certificate verifier")
        with self.writer_lock():
            # Snapshot first: verification and publication always operate on identical bytes.
            with tempfile.TemporaryDirectory(prefix=".publish-", dir=str(self.root)) as temporary:
                staged = Path(temporary) / "version"
                staged.mkdir()
                package = staged / "package.ahup"
                shutil.copyfile(source, package)
                with package.open("r+b") as stream:
                    os.fsync(stream.fileno())
                record = inspect_package(package, self.verifier)
                manifest = record["manifest"]
                directory = self.packages / manifest["id"] / str(manifest["versionCode"])
                if directory.exists():
                    raise MarketError("Version is immutable; publish a new versionCode",
                                      "version_exists", 409)
                record["publishedAt"] = utc_now()
                atomic_json(staged / "metadata.json", record)
                directory.parent.mkdir(parents=True, exist_ok=True)
                # A same-filesystem directory rename makes payload + metadata visible together.
                os.rename(str(staged), str(directory))
                return self.public_record(record)

    def withdraw(self, identifier, version, reason):
        identifier = plugin_id(identifier)
        positive_int(version, "versionCode")
        if not isinstance(reason, str) or not reason.strip() or len(reason) > 2048:
            raise MarketError("A withdrawal reason is required (maximum 2048 characters)")
        with self.writer_lock():
            directory = self.packages / identifier / str(version)
            if not (directory / "metadata.json").is_file():
                raise MarketError("Version not found", "not_found", 404)
            marker = directory / "withdrawal.json"
            if marker.exists():
                raise MarketError("Version is already withdrawn", "already_withdrawn", 409)
            value = {"id": identifier, "versionCode": version,
                     "reason": reason.strip(), "withdrawnAt": utc_now()}
            atomic_json(marker, value)
            return value

    def records(self, identifier=None, include_withdrawn=False):
        if identifier is not None:
            paths = (self.packages / plugin_id(identifier)).glob("*/metadata.json")
        else:
            paths = self.packages.glob("*/*/metadata.json")
        result = []
        for path in paths:
            # Storage is operator-controlled; never derive paths from submitted JSON.
            if (path.parent / "withdrawal.json").exists() and not include_withdrawn:
                continue
            record = json.loads(path.read_text(encoding="utf-8"))
            if include_withdrawn and (path.parent / "withdrawal.json").exists():
                record["withdrawal"] = json.loads((path.parent / "withdrawal.json").read_text(encoding="utf-8"))
            result.append(record)
        return sorted(result, key=lambda item: (item["manifest"]["id"], -item["manifest"]["versionCode"]))

    @staticmethod
    def public_record(record):
        manifest = record["manifest"]
        identifier, version = manifest["id"], manifest["versionCode"]
        return {"manifest": manifest,
                "download": {"url": "/v1/packages/%s/%s/%s.ahup" % (identifier, version, record["sha256"]),
                             "sha256": record["sha256"], "size": record["size"]},
                "publishedAt": record["publishedAt"],
                "signerCertificateSha256": record["signerCertificateSha256"]}

    def catalog(self, client):
        chosen = {}
        for record in self.records():
            manifest = record["manifest"]
            if client.compatible(manifest) and manifest["id"] not in chosen:
                chosen[manifest["id"]] = self.public_record(record)
        return {"schemaVersion": 1, "plugins": list(chosen.values()), "required": list(self.required)}

    def detail(self, identifier, client):
        candidates = [self.public_record(record) for record in self.records(identifier)
                      if client.compatible(record["manifest"])]
        if not candidates:
            raise MarketError("No compatible version found", "not_found", 404)
        return {"schemaVersion": 1, "id": identifier, "versions": candidates}

    def resolve(self, request, updates=False):
        client = Client(request)
        installed = request.get("installed", {})
        if not isinstance(installed, dict) or len(installed) > 128:
            raise MarketError("installed must be an id-to-versionCode object (maximum 128)")
        for identifier, version in installed.items():
            plugin_id(identifier)
            positive_int(version, "installed versionCode")
        requested = request.get("requested", list(installed) if updates else None)
        if not isinstance(requested, list) or len(requested) > 128:
            raise MarketError("requested must be an array of plugin ids (maximum 128)")
        requested = list(dict.fromkeys(plugin_id(identifier) for identifier in requested))
        # Keep every installed plugin in the graph: upgrading a shared dependency
        # must not silently break a feature the user did not select on this screen.
        required = list(dict.fromkeys(requested + list(installed) + list(self.required)))
        initial = {identifier: (installed.get(identifier, 1), INT_MAX) for identifier in required}
        candidates = {}
        for record in self.records():
            manifest = record["manifest"]
            if client.compatible(manifest):
                candidates.setdefault(manifest["id"], []).append(record)
        attempts = [0]

        def search(selected, constraints):
            attempts[0] += 1
            if attempts[0] > 20000:
                raise MarketError("Dependency search limit exceeded", "resolution_limit", 422)
            if len(constraints) > 128:
                raise MarketError("Dependency set exceeds 128 plugins", "resolution_limit", 422)
            for identifier, record in selected.items():
                low, high = constraints[identifier]
                if not low <= record["manifest"]["versionCode"] <= high:
                    return None
            missing = [identifier for identifier in constraints if identifier not in selected]
            if not missing:
                try:
                    return order(selected)
                except MarketError:
                    return None
            identifier = min(missing, key=lambda value: len(candidates.get(value, [])))
            low, high = constraints[identifier]
            for record in candidates.get(identifier, []):
                manifest = record["manifest"]
                if not low <= manifest["versionCode"] <= high:
                    continue
                next_constraints = dict(constraints)
                valid = True
                for dependency in manifest["dependencies"]:
                    dependency_id = dependency["id"]
                    previous = next_constraints.get(dependency_id, (installed.get(dependency_id, 1), INT_MAX))
                    bounds = (max(previous[0], dependency["minVersion"]),
                              min(previous[1], dependency.get("maxVersion", INT_MAX)))
                    if bounds[0] > bounds[1]:
                        valid = False
                        break
                    next_constraints[dependency_id] = bounds
                if valid:
                    result = search(dict(selected, **{identifier: record}), next_constraints)
                    if result is not None:
                        return result
            return None

        def order(selected):
            ordered, visiting, visited = [], set(), set()

            def visit(identifier):
                if identifier in visiting:
                    raise MarketError("Dependency cycle", "dependency_cycle", 409)
                if identifier in visited:
                    return
                visiting.add(identifier)
                for dependency in selected[identifier]["manifest"]["dependencies"]:
                    visit(dependency["id"])
                visiting.remove(identifier)
                visited.add(identifier)
                ordered.append(selected[identifier])

            for identifier in sorted(selected):
                visit(identifier)
            return ordered

        resolved = search({}, initial)
        if resolved is None:
            raise MarketError("No compatible dependency combination satisfies the requested and installed versions",
                              "dependency_conflict", 409)
        public = [self.public_record(record) for record in resolved]
        return {"schemaVersion": 1, "requested": requested, "required": list(self.required),
                "plugins": public,
                "downloads": [record for record in public
                              if installed.get(record["manifest"]["id"]) != record["manifest"]["versionCode"]]}

    def package(self, identifier, version, sha256):
        identifier = plugin_id(identifier)
        positive_int(version, "versionCode")
        directory = self.packages / identifier / str(version)
        if not (directory / "metadata.json").is_file():
            raise MarketError("Package not found", "not_found", 404)
        if (directory / "withdrawal.json").exists():
            raise MarketError("Package withdrawn", "withdrawn", 410)
        record = json.loads((directory / "metadata.json").read_text(encoding="utf-8"))
        if sha256 != record["sha256"]:
            raise MarketError("Package not found", "not_found", 404)
        path = directory / "package.ahup"
        if not path.is_file() or path.stat().st_size != record["size"]:
            raise MarketError("Package storage is incomplete", "storage_corrupt", 503)
        return path, record
