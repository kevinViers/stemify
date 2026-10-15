"""Queue of separation jobs as a QML list model."""

from dataclasses import dataclass, field

from PySide6.QtCore import QAbstractListModel, QByteArray, QModelIndex, Qt, Signal


@dataclass
class Job:
    id: str
    file: str
    file_name: str
    model_id: str
    model_label: str
    two_stems: bool
    device_id: str
    cache_dir: str
    status: str = "queued"  # queued | running | done | error | cancelled
    progress: float = 0.0
    stage: str = "Waiting"
    message: str = ""
    started: float = 0.0
    elapsed: float = 0.0
    result: dict = field(default_factory=dict)
    exported_to: str = ""


ROLES = ["jobId", "fileName", "filePath", "modelLabel", "status", "progress", "stage", "message", "elapsed"]
_ATTR = {"jobId": "id", "fileName": "file_name", "filePath": "file", "modelLabel": "model_label"}


class JobsModel(QAbstractListModel):
    countChanged = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.jobs: list[Job] = []
        self._roles = {Qt.ItemDataRole.UserRole + i: name for i, name in enumerate(ROLES)}

    def roleNames(self):
        return {k: QByteArray(v.encode()) for k, v in self._roles.items()}

    def rowCount(self, parent=QModelIndex()):
        return 0 if parent.isValid() else len(self.jobs)

    def data(self, index, role=Qt.ItemDataRole.DisplayRole):
        if not index.isValid() or role not in self._roles:
            return None
        name = self._roles[role]
        return getattr(self.jobs[index.row()], _ATTR.get(name, name))

    def get(self, job_id) -> Job | None:
        return next((j for j in self.jobs if j.id == job_id), None)

    def row_of(self, job_id) -> int:
        return next((i for i, j in enumerate(self.jobs) if j.id == job_id), -1)

    def add(self, job: Job):
        n = len(self.jobs)
        self.beginInsertRows(QModelIndex(), n, n)
        self.jobs.append(job)
        self.endInsertRows()
        self.countChanged.emit()

    def remove(self, job_id):
        row = self.row_of(job_id)
        if row >= 0:
            self.beginRemoveRows(QModelIndex(), row, row)
            del self.jobs[row]
            self.endRemoveRows()
            self.countChanged.emit()

    def update(self, job_id, **changes):
        row = self.row_of(job_id)
        if row < 0:
            return
        job = self.jobs[row]
        for k, v in changes.items():
            setattr(job, k, v)
        idx = self.index(row)
        roles = [r for r, name in self._roles.items() if _ATTR.get(name, name) in changes or name in changes]
        self.dataChanged.emit(idx, idx, roles)
