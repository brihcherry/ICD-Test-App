import { useMemo, useState, useEffect, useRef } from "react";
import { useInsight } from "@semoss/sdk/react";
import { toast } from "sonner";
import { ModelResultsEditor } from "./ModelResultsEditor";
import { TableInspectorTools } from "./TableInspectorTools";
import { Button } from "./ui/button";
import type {
	DeletedResultRowEntry,
	DownloadJsonPayload,
	GetTablesResponse,
	ModelBatchOutput,
	ModelResultRow,
	SendToModelResponse,
	TableCandidate,
} from "./types";

const MAX_FILE_SIZE_MB = 25;
const PLACEHOLDER_SYSTEM_OPTIONS = [
	"Claims Gateway",
	"Patient Master",
	"Billing Hub",
	"Pharmacy Connector",
	"Eligibility Service",
];

const formatFileSize = (bytes: number) => {
	if (bytes < 1024) return `${bytes} B`;
	const units = ["KB", "MB", "GB"];
	let value = bytes / 1024;
	let unitIndex = 0;
	while (value >= 1024 && unitIndex < units.length - 1) {
		value /= 1024;
		unitIndex += 1;
	}
	return `${value.toFixed(1)} ${units[unitIndex]}`;
};

type SearchableSystemSelectProps = {
	id: string;
	label: string;
	value: string;
	options: string[];
	searchValue: string;
	placeholder: string;
	onSearchChange: (value: string) => void;
	onChange: (value: string) => void;
};

const SearchableSystemSelect = ({
	id,
	label,
	value,
	options,
	searchValue,
	placeholder,
	onSearchChange,
	onChange,
}: SearchableSystemSelectProps) => {
	const filteredOptions = options.filter((option) =>
		option.toLowerCase().includes(searchValue.trim().toLowerCase()),
	);

	return (
		<div>
			<label htmlFor={id} className="text-sm font-medium">{label}</label>
			<input
				type="text"
				value={searchValue}
				onChange={(event) => onSearchChange(event.target.value)}
				placeholder="Search systems..."
				className="mt-1 h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
			/>
			<select
				id={id}
				value={value}
				onChange={(event) => onChange(event.target.value)}
				className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
			>
				<option value="">{placeholder}</option>
				{filteredOptions.map((systemName) => (
					<option key={systemName} value={systemName}>{systemName}</option>
				))}
			</select>
		</div>
	);
};

const fileToBase64 = (file: File): Promise<string> =>
	new Promise((resolve, reject) => {
		const reader = new FileReader();
		reader.onload = () => {
			if (typeof reader.result !== "string") {
				reject(new Error("Unable to read file payload."));
				return;
			}
			const commaIndex = reader.result.indexOf(",");
			resolve(commaIndex >= 0 ? reader.result.slice(commaIndex + 1) : reader.result);
		};
		reader.onerror = () => reject(new Error("Unable to read file payload."));
		reader.readAsDataURL(file);
	});

const isWordDocument = (file: File) => {
	const validMimeTypes = [
		"application/msword",
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
	];
	const lowerName = file.name.toLowerCase();
	const hasWordExtension = lowerName.endsWith(".doc") || lowerName.endsWith(".docx");
	return validMimeTypes.includes(file.type) || hasWordExtension;
};

const findGetTablesPayload = (node: unknown, depth = 0): GetTablesResponse | null => {
	if (depth > 8 || node == null) return null;
	if (Array.isArray(node)) {
		for (const item of node) {
			const found = findGetTablesPayload(item, depth + 1);
			if (found) return found;
		}
		return null;
	}
	if (typeof node === "object") {
		const asRecord = node as Record<string, unknown>;
		if (Array.isArray(asRecord.tableCandidates)) {
			return {
				tableCount: typeof asRecord.tableCount === "number" ? asRecord.tableCount : asRecord.tableCandidates.length,
				tableCandidates: asRecord.tableCandidates as TableCandidate[],
			};
		}
		for (const value of Object.values(asRecord)) {
			const found = findGetTablesPayload(value, depth + 1);
			if (found) return found;
		}
	}
	return null;
};

const findSendToModelPayload = (node: unknown, depth = 0): SendToModelResponse | null => {
	if (depth > 10 || node == null) return null;
	if (Array.isArray(node)) {
		for (const item of node) {
			const found = findSendToModelPayload(item, depth + 1);
			if (found) return found;
		}
		return null;
	}
	if (typeof node === "object") {
		const asRecord = node as Record<string, unknown>;
		if (Array.isArray(asRecord.batches)) {
			const batches = asRecord.batches as SendToModelResponse["batches"];
			return {
				totalBatches: typeof asRecord.totalBatches === "number" ? asRecord.totalBatches : batches.length,
				batches,
			};
		}
		for (const value of Object.values(asRecord)) {
			const found = findSendToModelPayload(value, depth + 1);
			if (found) return found;
		}
	}
	return null;
};

const isLikelyTableText = (value: string) => {
	const text = value.trim().toLowerCase();
	return text.includes("|")
		&& text.includes("data element")
		&& text.includes("variable type")
		&& text.includes("field length")
		&& text.includes("delimited")
		&& text.includes("value range")
		&& text.includes("data subject area");
};

const extractTextFromPixelReturn = (node: unknown): string => {
	const candidates: string[] = [];

	const visit = (value: unknown, parentKey = "") => {
		if (value == null) return;
		if (typeof value === "string") {
			// Only capture strings from known response-bearing keys to avoid pixelExpression noise.
			if (["response", "text", "content", "result", "value"].includes(parentKey)) {
				candidates.push(value);
			}
			return;
		}
		if (Array.isArray(value)) {
			for (const item of value) visit(item, parentKey);
			return;
		}
		if (typeof value === "object") {
			const record = value as Record<string, unknown>;
			if (record.output && typeof record.output === "object") {
				visit(record.output, "output");
			}
			if (record.parts && Array.isArray(record.parts)) {
				visit(record.parts, "parts");
			}
			for (const [key, child] of Object.entries(record)) {
				visit(child, key);
			}
		}
	};

	visit(node);

	const preferred = candidates.find((value) => isLikelyTableText(value));
	if (preferred) return preferred;

	const withPipes = candidates.find((value) => value.includes("|"));
	if (withPipes) return withPipes;

	return candidates.find((value) => value.trim().length > 0) ?? "";
};

const splitPipeRow = (rowLine: string): string[] => {
	const trimmed = rowLine.trim().replace(/^\|/, "").replace(/\|$/, "");
	const protectedPipes = trimmed.replace(/\\\|/g, "__ESCAPED_PIPE__");
	return protectedPipes
		.split("|")
		.map((cell) => cell.replace(/__ESCAPED_PIPE__/g, "|").trim());
};

const isSeparatorRow = (cells: string[]) =>
	cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell.replace(/\s+/g, "")));

const headerIndex = (headers: string[], names: string[]) => {
	for (let i = 0; i < headers.length; i++) {
		const normalized = headers[i].trim().toLowerCase();
		if (names.includes(normalized)) return i;
	}
	return -1;
};

const parseMarkdownRows = (rawOutput: string, batchNumber: number): { rows: ModelResultRow[]; warnings: string[] } => {
	const warnings: string[] = [];
	const lines = rawOutput
		.split(/\r?\n/)
		.map((line) => line.trim())
		.filter((line) => line.length > 0);

	const tableLines = lines.filter((line) => line.includes("|"));
	if (tableLines.length < 2) {
		warnings.push(`Batch ${batchNumber}: no markdown table found.`);
		return { rows: [], warnings };
	}

	const headerCells = splitPipeRow(tableLines[0]);
	const secondRowCells = splitPipeRow(tableLines[1]);
	const hasSeparator = isSeparatorRow(secondRowCells);

	const dataElementIdx = headerIndex(headerCells, ["data element", "dataelement", "field name", "element"]);
	const descriptionIdx = headerIndex(headerCells, ["description", "definition", "data element description", "data element definition"]);
	const variableTypeIdx = headerIndex(headerCells, ["variable type", "variabletype", "data type", "datatype", "type"]);
	const fieldLengthIdx = headerIndex(headerCells, ["field length", "fieldlength", "length"]);
	const delimitedIdx = headerIndex(headerCells, ["delimited", "delimiter", "is delimited", "isdelimited"]);
	const valueRangeIdx = headerIndex(headerCells, ["value range", "valuerange", "range", "allowed values", "domain"]);
	const subjectAreaIdx = headerIndex(headerCells, ["data subject area", "datasubjectarea", "subject area", "subjectarea", "data subject area primary", "dsa1", "primary dsa"]);
	const subjectAreaAlt1Idx = headerIndex(headerCells, ["data subject area 2", "data subject area alt 1", "alternate dsa 1", "dsa2", "subject area 2"]);
	const subjectAreaAlt2Idx = headerIndex(headerCells, ["data subject area 3", "data subject area alt 2", "alternate dsa 2", "dsa3", "subject area 3"]);
	const confidenceIdx = headerIndex(headerCells, ["confidence", "confidence level", "confidencelevel"]);

	if (dataElementIdx < 0 || descriptionIdx < 0 || variableTypeIdx < 0 || fieldLengthIdx < 0 || delimitedIdx < 0 || valueRangeIdx < 0 || subjectAreaIdx < 0 || subjectAreaAlt1Idx < 0 || subjectAreaAlt2Idx < 0) {
		warnings.push(
			`Batch ${batchNumber}: table headers missing required columns. Found: ${headerCells.join(", ") || "(none)"}.`,
		);
		return { rows: [], warnings };
	}

	const startIndex = hasSeparator ? 2 : 1;
	const rows: ModelResultRow[] = [];

	for (let i = startIndex; i < tableLines.length; i++) {
		const cells = splitPipeRow(tableLines[i]);
		if (cells.every((cell) => cell === "")) continue;

		const dataElement = cells[dataElementIdx] ?? "";
		const description = cells[descriptionIdx] ?? "";
		const variableType = cells[variableTypeIdx] ?? "";
		const fieldLength = cells[fieldLengthIdx] ?? "";
		const delimited = cells[delimitedIdx] ?? "";
		const valueRange = cells[valueRangeIdx] ?? "";
		const dataSubjectArea = cells[subjectAreaIdx] ?? "";
		const dataSubjectAreaAlt1 = cells[subjectAreaAlt1Idx] ?? "";
		const dataSubjectAreaAlt2 = cells[subjectAreaAlt2Idx] ?? "";
		const confidence = confidenceIdx >= 0 ? cells[confidenceIdx] ?? "" : "";
		if (!dataElement && !description && !variableType && !fieldLength && !delimited && !valueRange && !dataSubjectArea && !dataSubjectAreaAlt1 && !dataSubjectAreaAlt2 && !confidence) continue;

		rows.push({
			id: `${batchNumber}-${i}-${Math.random().toString(36).slice(2, 8)}`,
			dataElement,
			description,
			variableType,
			fieldLength,
			delimited,
			valueRange,
			dataSubjectArea,
			dataSubjectAreaAlt1,
			dataSubjectAreaAlt2,
			confidence,
		});
	}

	if (rows.length === 0) {
		warnings.push(`Batch ${batchNumber}: markdown table parsed but no data rows found.`);
	}

	return { rows, warnings };
};

const findDownloadPayload = (node: unknown, depth = 0): DownloadJsonPayload | null => {
	if (depth > 10 || node == null) return null;
	if (Array.isArray(node)) {
		for (const item of node) {
			const found = findDownloadPayload(item, depth + 1);
			if (found) return found;
		}
		return null;
	}
	if (typeof node === "object") {
		const asRecord = node as Record<string, unknown>;
		if (typeof asRecord.fileContentBase64 === "string") {
			return {
				fileName: typeof asRecord.fileName === "string" ? asRecord.fileName : "model-results.json",
				mimeType: typeof asRecord.mimeType === "string" ? asRecord.mimeType : "application/json",
				fileContentBase64: asRecord.fileContentBase64,
				byteCount: typeof asRecord.byteCount === "number" ? asRecord.byteCount : 0,
			};
		}
		for (const value of Object.values(asRecord)) {
			const found = findDownloadPayload(value, depth + 1);
			if (found) return found;
		}
	}
	return null;
};

const findDsaNames = (node: unknown, depth = 0): string[] => {
	if (depth > 10 || node == null) return [];
	if (Array.isArray(node)) {
		for (const item of node) {
			const found = findDsaNames(item, depth + 1);
			if (found.length > 0) return found;
		}
		return [];
	}
	if (typeof node === "object") {
		const asRecord = node as Record<string, unknown>;
		if (Array.isArray(asRecord.dsaNames) && asRecord.dsaNames.every((x) => typeof x === "string")) {
			return asRecord.dsaNames as string[];
		}
		for (const value of Object.values(asRecord)) {
			const found = findDsaNames(value, depth + 1);
			if (found.length > 0) return found;
		}
	}
	return [];
};

const findSystemNames = (node: unknown, depth = 0): string[] => {
	if (depth > 10 || node == null) return [];
	if (Array.isArray(node)) {
		for (const item of node) {
			const found = findSystemNames(item, depth + 1);
			if (found.length > 0) return found;
		}
		return [];
	}
	if (typeof node === "object") {
		const asRecord = node as Record<string, unknown>;
		if (Array.isArray(asRecord.systemNames) && asRecord.systemNames.every((x) => typeof x === "string")) {
			return asRecord.systemNames as string[];
		}
		for (const value of Object.values(asRecord)) {
			const found = findSystemNames(value, depth + 1);
			if (found.length > 0) return found;
		}
	}
	return [];
};

const base64ToBlob = (base64: string, mimeType: string) => {
	const binary = atob(base64);
	const bytes = new Uint8Array(binary.length);
	for (let i = 0; i < binary.length; i++) {
		bytes[i] = binary.charCodeAt(i);
	}
	return new Blob([bytes], { type: mimeType });
};

const toRowSnapshotMap = (rows: ModelResultRow[]) =>
	rows.reduce<Record<string, ModelResultRow>>((acc, row) => {
		acc[row.id] = { ...row };
		return acc;
	}, {});

const resettableFields: Array<keyof ModelResultRow> = [
	"dataElement",
	"description",
	"variableType",
	"fieldLength",
	"delimited",
	"valueRange",
	"dataSubjectArea",
	"dataSubjectAreaAlt1",
	"dataSubjectAreaAlt2",
	"confidence",
];

export const ExampleComponent = () => {
	const { actions } = useInsight() as {
		actions?: { run: (pixel: string) => Promise<unknown> };
	};
	const fileInputRef = useRef<HTMLInputElement | null>(null);

	const [selectedFile, setSelectedFile] = useState<File | null>(null);
	const [workflowStage, setWorkflowStage] = useState<"setup" | "workbench">("setup");
	const [providerSystem, setProviderSystem] = useState("");
	const [consumerSystem, setConsumerSystem] = useState("");
	const [providerSearch, setProviderSearch] = useState("");
	const [consumerSearch, setConsumerSearch] = useState("");
	const [systemOptions, setSystemOptions] = useState<string[]>(PLACEHOLDER_SYSTEM_OPTIONS);
	const [isLoadingTables, setIsLoadingTables] = useState(false);
	const [isProcessing, setIsProcessing] = useState(false);
	const [dictionaryFile, setDictionaryFile] = useState<File | null>(null);
	const [isLoadingDictionary, setIsLoadingDictionary] = useState(false);
	const [dictionaryLoaded, setDictionaryLoaded] = useState(false);
	const [tableCandidates, setTableCandidates] = useState<TableCandidate[]>([]);
	const [selectedTableIndexes, setSelectedTableIndexes] = useState<number[]>([]);
	const [hasScannedTables, setHasScannedTables] = useState(false);
	const [viewMode, setViewMode] = useState<"selection" | "results">("selection");
	const [modelResultRows, setModelResultRows] = useState<ModelResultRow[]>([]);
	const [originalModelRowsById, setOriginalModelRowsById] = useState<Record<string, ModelResultRow>>({});
	const [deletedResultRows, setDeletedResultRows] = useState<DeletedResultRowEntry[]>([]);
	const [modelBatchOutputs, setModelBatchOutputs] = useState<ModelBatchOutput[]>([]);
	const [isSaving, setIsSaving] = useState(false);
	const [dsaOptions, setDsaOptions] = useState<string[]>([]);

	const modifiedRowIds = useMemo(() => {
		const next = new Set<string>();
		for (const row of modelResultRows) {
			const original = originalModelRowsById[row.id];
			if (original) {
				if (resettableFields.some((field) => row[field] !== original[field])) {
					next.add(row.id);
				}
				continue;
			}

			if (resettableFields.some((field) => (row[field] ?? "").trim().length > 0)) {
				next.add(row.id);
			}
		}
		return next;
	}, [modelResultRows, originalModelRowsById]);

	const maxFileSizeBytes = useMemo(() => MAX_FILE_SIZE_MB * 1024 * 1024, []);

	// Auto-load Data Subject Areas from assets on component mount
	useEffect(() => {
		const loadDSA = async () => {
			if (!actions?.run) return;
			try {
				const [, namesResult] = await Promise.all([
					actions.run("GetDataDictionary()"),
					actions.run("GetDataSubjectAreaNames()"),
				]);
				setDictionaryFile(new File([], "Data Subject Area Definitions.docx"));
				setDictionaryLoaded(true);
				const names = findDsaNames(namesResult);
				if (names.length > 0) setDsaOptions(names);
			} catch (error) {
				console.error("Failed to auto-load data subject areas:", error);
			}

			try {
				const systemsResult = await actions.run("GetExistingSystems()");
				const names = findSystemNames(systemsResult)
					.map((value) => value.trim())
					.filter((value) => value.length > 0);
				if (names.length > 0) {
					setSystemOptions(Array.from(new Set(names)));
				}
			} catch (error) {
				console.warn("Failed to load systems from GetExistingSystems. Using placeholder options.", error);
			}
		};
		loadDSA();
	}, [actions]);

	const handleSelectIcdFile = (file: File) => {
		if (!isWordDocument(file)) {
			toast.error("Please upload a .doc or .docx Word document.");
			return;
		}
		if (file.size > maxFileSizeBytes) {
			toast.error(`File is too large. Max size is ${MAX_FILE_SIZE_MB} MB.`);
			return;
		}
		setSelectedFile(file);
		setTableCandidates([]);
		setSelectedTableIndexes([]);
		setHasScannedTables(false);
		setViewMode("selection");
		setModelResultRows([]);
		setOriginalModelRowsById({});
		setDeletedResultRows([]);
		setModelBatchOutputs([]);
	};

	const clearIcdSelection = () => {
		setSelectedFile(null);
		setTableCandidates([]);
		setSelectedTableIndexes([]);
		setHasScannedTables(false);
		setViewMode("selection");
		setModelResultRows([]);
		setOriginalModelRowsById({});
		setDeletedResultRows([]);
		setModelBatchOutputs([]);
		setIsProcessing(false);
		setIsLoadingTables(false);
	};

	const handleClear = async () => {
		if (actions?.run) {
			try {
				await actions.run("ClearTableParseCache()");
			} catch {
				// Local reset still proceeds
			}
		}
		clearIcdSelection();
		setProviderSystem("");
		setConsumerSystem("");
		setProviderSearch("");
		setConsumerSearch("");
		setWorkflowStage("setup");
		setIsSaving(false);
		if (fileInputRef.current) fileInputRef.current.value = "";
	};

	const handleUploadDictionary = async (file: File) => {
		if (!file.name.toLowerCase().endsWith(".docx")) {
			toast.error("Data dictionary must be a .docx file.");
			return;
		}
		if (!actions?.run) {
			toast.error("SEMOSS actions are not initialized yet.");
			return;
		}
		setIsLoadingDictionary(true);
		try {
			const base64 = await fileToBase64(file);
			const command = `GetDataDictionary(fileName=${JSON.stringify(file.name)}, fileContentBase64=${JSON.stringify(base64)})`;
			await actions.run(command);
			setDictionaryFile(file);
			setDictionaryLoaded(true);
			toast.success(`Data dictionary "${file.name}" loaded.`);
		} catch (error) {
			const message = error instanceof Error ? error.message : "Failed to load data dictionary.";
			toast.error(message);
		} finally {
			setIsLoadingDictionary(false);
		}
	};

	const handleGetTables = async (onSuccess?: () => void) => {
		if (!selectedFile) {
			toast.error("Select an ICD document before choosing tables.");
			return;
		}
		if (!selectedFile.name.toLowerCase().endsWith(".docx")) {
			toast.error("GetTables currently supports .docx files only.");
			return;
		}
		if (!actions?.run) {
			toast.error("SEMOSS actions are not initialized yet.");
			return;
		}
		setIsLoadingTables(true);
		try {
			const base64 = await fileToBase64(selectedFile);
			const command = `GetTables(fileName=${JSON.stringify(selectedFile.name)}, fileContentBase64=${JSON.stringify(base64)})`;
			const pixelReturn = await actions.run(command);
			const payload = findGetTablesPayload(pixelReturn);
			if (!payload) throw new Error("GetTables did not return tableCandidates.");
			setTableCandidates(payload.tableCandidates);
			setSelectedTableIndexes([]);
			setHasScannedTables(true);
			onSuccess?.();
		} catch (error) {
			setHasScannedTables(false);
			const message = error instanceof Error ? error.message : "Failed to identify document tables.";
			toast.error(message);
		} finally {
			setIsLoadingTables(false);
		}
	};

	const handleProcessIcd = async () => {
		if (!selectedFile) {
			toast.error("Upload an ICD document before processing.");
			return;
		}
		if (!providerSystem || !consumerSystem) {
			toast.error("Select both provider and consumer systems.");
			return;
		}
		if (providerSystem === consumerSystem) {
			toast.error("Provider and consumer systems must be different.");
			return;
		}

		await handleGetTables(() => {
			setWorkflowStage("workbench");
			setViewMode("selection");
		});
	};

	const handleBackToSetup = () => {
		setViewMode("selection");
		setWorkflowStage("setup");
	};

	const handleGetDataElements = async () => {
		if (!dictionaryLoaded) {
			toast.error("Upload and load a data dictionary before extracting data elements.");
			return;
		}
		if (selectedTableIndexes.length === 0) {
			toast.error("Select at least one table.");
			return;
		}
		if (!actions?.run) {
			toast.error("SEMOSS actions are not initialized yet.");
			return;
		}
		setIsProcessing(true);
		try {
			const tableIndexesPayload = `[${selectedTableIndexes.map((i) => Number(i)).join(",")}]`;

			await actions.run(`ExtractDataElements(tableIndexes=${tableIndexesPayload})`);

			const result = await actions.run(`SendDataElementsToModel(tableIndexes=${tableIndexesPayload})`);
			const payload = findSendToModelPayload(result);
			if (!payload) throw new Error("SendDataElementsToModel did not return batch payload.");

			const allRows: ModelResultRow[] = [];
			const diagnostics: ModelBatchOutput[] = [];
			let executedBatchCount = 0;

			for (const batch of payload.batches) {
				if (!batch.llmCommand) continue;
				executedBatchCount += 1;
				try {
					const llmResult = await actions.run(batch.llmCommand);
					const rawOutput = extractTextFromPixelReturn(llmResult);
					const parsed = parseMarkdownRows(rawOutput, batch.batchNumber ?? 0);
					allRows.push(...parsed.rows);
					diagnostics.push({
						batchNumber: batch.batchNumber ?? 0,
						rawOutput,
						parsedRowCount: parsed.rows.length,
						warnings: parsed.warnings,
					});
				} catch (batchError) {
					const message =
						batchError instanceof Error ? batchError.message : "LLM batch execution failed.";
					diagnostics.push({
						batchNumber: batch.batchNumber ?? 0,
						rawOutput: "",
						parsedRowCount: 0,
						warnings: [`Batch ${batch.batchNumber ?? 0}: ${message}`],
					});
				}
			}

			if (executedBatchCount === 0) {
				throw new Error("No LLM batches were executed.");
			}

			setModelResultRows(allRows);
			setOriginalModelRowsById(toRowSnapshotMap(allRows));
			setDeletedResultRows([]);
			setModelBatchOutputs(diagnostics);
			setViewMode("results");

			toast.success(
				payload.totalBatches != null
					? `Extracted data elements from ${selectedTableIndexes.length} table(s) in ${payload.totalBatches} batch(es).`
					: `Extracted data elements from ${selectedTableIndexes.length} table(s).`,
			);

			if (allRows.length === 0) {
				toast.warning("Model responses were received, but no markdown rows were parsed. Review diagnostics.");
			}
		} catch (error) {
			const message = error instanceof Error ? error.message : "Failed to extract data elements.";
			toast.error(message);
		} finally {
			setIsProcessing(false);
		}
	};

	const handleChangeResultCell = (
		id: string,
		field: "dataElement" | "description" | "variableType" | "fieldLength" | "delimited" | "valueRange" | "dataSubjectArea" | "confidence",
		value: string,
	) => {
		setModelResultRows((current) =>
			current.map((row) => (row.id === id ? { ...row, [field]: value } : row)),
		);
	};

	const handleAddResultRow = () => {
		setModelResultRows((current) => [
			...current,
			{
				id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
				dataElement: "",
				description: "",
				variableType: "",
				fieldLength: "",
				delimited: "",
				valueRange: "",
				dataSubjectArea: "",
				dataSubjectAreaAlt1: "",
				dataSubjectAreaAlt2: "",
				confidence: "",
			},
		]);
	};

	const handleResetResultRow = (id: string) => {
		setModelResultRows((current) =>
			current.map((row) => {
				if (row.id !== id) return row;
				const original = originalModelRowsById[id];
				if (original) {
					return { ...original };
				}

				const clearedRow = { ...row };
				for (const field of resettableFields) {
					clearedRow[field] = "";
				}
				return clearedRow;
			}),
		);
	};

	const restoreDeletedEntry = (entry: DeletedResultRowEntry) => {
		setModelResultRows((current) => {
			const insertAt = Math.max(0, Math.min(entry.index, current.length));
			const next = [...current];
			next.splice(insertAt, 0, entry.row);
			return next;
		});
	};

	const handleRestoreDeletedRow = (deleteToken?: number) => {
		let restored = false;
		setDeletedResultRows((current) => {
			if (current.length === 0) return current;
			const next = [...current];

			let restoreIndex = next.length - 1;
			if (deleteToken != null) {
				restoreIndex = -1;
				for (let i = next.length - 1; i >= 0; i--) {
					if (next[i].deleteToken === deleteToken) {
						restoreIndex = i;
						break;
					}
				}
			}

			if (restoreIndex < 0) return current;
			const [entry] = next.splice(restoreIndex, 1);
			restoreDeletedEntry(entry);
			restored = true;
			return next;
		});

		if (restored) {
			toast.success("Deleted row restored.");
		}
	};

	const handleDeleteResultRow = (id: string) => {
		const index = modelResultRows.findIndex((row) => row.id === id);
		if (index < 0) return;
		const row = modelResultRows[index];
		const deleteToken = Date.now() + Math.random();

		setDeletedResultRows((current) => [
			...current,
			{ row, index, deleteToken, deletedAt: new Date().toISOString() },
		]);
		setModelResultRows((current) => current.filter((candidate) => candidate.id !== id));

		toast("Row deleted.", {
			action: {
				label: "Undo",
				onClick: () => handleRestoreDeletedRow(deleteToken),
			},
		});
	};

	const handleBackToSelection = () => {
		setViewMode("selection");
	};

	const handleDownloadResultsJson = async () => {
		if (!actions?.run) {
			toast.error("SEMOSS actions are not initialized yet.");
			return;
		}
		setIsSaving(true);
		try {
			const cleanedRows = modelResultRows.map((row) => ({
				dataElement: row.dataElement,
				description: row.description,
				variableType: row.variableType,
				fieldLength: row.fieldLength,
				delimited: row.delimited,
				valueRange: row.valueRange,
				dataSubjectArea: row.dataSubjectArea,
				dataSubjectAreaAlt1: row.dataSubjectAreaAlt1,
				dataSubjectAreaAlt2: row.dataSubjectAreaAlt2,
				confidence: row.confidence,
			}));
			const payload = {
				documentName: selectedFile?.name ?? "ICD",
				generatedAt: new Date().toISOString(),
				selectedTableIndexes,
				rows: cleanedRows,
			};
			const command = `DownloadModelResultsJson(payload=${JSON.stringify(JSON.stringify(payload))})`;
			const result = await actions.run(command);
			const downloadPayload = findDownloadPayload(result);
			if (!downloadPayload) throw new Error("DownloadModelResultsJson did not return file payload.");

			const blob = base64ToBlob(downloadPayload.fileContentBase64, downloadPayload.mimeType);
			const url = URL.createObjectURL(blob);
			const anchor = document.createElement("a");
			anchor.href = url;
			anchor.download = downloadPayload.fileName;
			document.body.appendChild(anchor);
			anchor.click();
			document.body.removeChild(anchor);
			URL.revokeObjectURL(url);
			toast.success(`Downloaded ${downloadPayload.fileName}.`);
		} catch (error) {
			const message = error instanceof Error ? error.message : "Failed to prepare JSON download.";
			toast.error(message);
		} finally {
			setIsSaving(false);
		}
	};

	const handleToggleTable = (tableIndex: number) => {
		setSelectedTableIndexes((current) =>
			current.includes(tableIndex)
				? current.filter((id) => id !== tableIndex)
				: [...current, tableIndex],
		);
	};

	return (
		<div className="flex flex-col h-screen">
			<div className="border-b bg-background p-4 md:p-6">
				<h1 className="text-2xl font-semibold tracking-tight md:text-3xl">ICD Interface Processing</h1>
				<p className="mt-2 text-sm text-muted-foreground">
					{workflowStage === "setup"
						? "Upload an ICD and select the connected systems before processing."
						: "Review detected tables and continue with data element extraction."}
				</p>
			</div>

			{workflowStage === "setup" ? (
				<div className="flex flex-1 items-start justify-center overflow-y-auto bg-muted/20 p-4 md:p-10">
					<div className="w-full max-w-3xl rounded-xl border bg-card p-6 shadow-sm md:p-8">
						<h2 className="text-xl font-semibold">Process ICD</h2>
						<p className="mt-1 text-sm text-muted-foreground">
							Upload one ICD document and select the two systems connected by the interface.
						</p>

						<div className="mt-6 space-y-5">
							<div>
								<p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">ICD Document</p>
								<div className="mt-2 flex flex-col gap-3 rounded-lg border p-4">
									<input
										ref={fileInputRef}
										type="file"
										accept=".doc,.docx,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
										className="hidden"
										onChange={(event) => {
											const file = event.target.files?.[0];
											if (file) handleSelectIcdFile(file);
										}}
									/>
									<div className="flex flex-wrap items-center gap-3">
										<Button
											type="button"
											variant="outline"
											onClick={() => fileInputRef.current?.click()}
										>
											{selectedFile ? "Replace ICD Document" : "Upload ICD Document"}
										</Button>
										{selectedFile && (
											<Button type="button" variant="ghost" onClick={handleClear}>
												Clear
											</Button>
										)}
									</div>
									{selectedFile ? (
										<p className="text-sm text-muted-foreground">
											Selected: <span className="font-medium text-foreground">{selectedFile.name}</span> ({formatFileSize(selectedFile.size)})
										</p>
									) : (
										<p className="text-sm text-muted-foreground">Accepted file types: .doc, .docx (max {MAX_FILE_SIZE_MB} MB)</p>
									)}
								</div>
							</div>

							<div className="grid gap-4 md:grid-cols-2">
								<SearchableSystemSelect
									id="provider-system"
									label="Provider System"
									value={providerSystem}
									options={systemOptions}
									searchValue={providerSearch}
									placeholder="Select provider system"
									onSearchChange={setProviderSearch}
									onChange={setProviderSystem}
								/>

								<SearchableSystemSelect
									id="consumer-system"
									label="Consumer System"
									value={consumerSystem}
									options={systemOptions}
									searchValue={consumerSearch}
									placeholder="Select consumer system"
									onSearchChange={setConsumerSearch}
									onChange={setConsumerSystem}
								/>
							</div>

							<p className="text-xs text-muted-foreground">
								System options are loaded from GetExistingSystems() when available, with placeholder fallback values.
							</p>

							<div className="flex justify-end">
								<Button
									type="button"
									onClick={handleProcessIcd}
									disabled={isLoadingTables || !selectedFile || !providerSystem || !consumerSystem || providerSystem === consumerSystem}
								>
									{isLoadingTables ? "Processing ICD..." : "Process ICD"}
								</Button>
							</div>
						</div>
					</div>
				</div>
			) : (
				<div className="flex flex-1 flex-col overflow-hidden">
					<div className="border-b bg-muted/20 px-4 py-3 md:px-6">
						<div className="flex flex-wrap items-center justify-between gap-3">
							<div className="text-sm text-muted-foreground">
								<span className="font-medium text-foreground">{selectedFile?.name}</span>
								<span className="mx-2">|</span>
								Provider: <span className="font-medium text-foreground">{providerSystem || "-"}</span>
								<span className="mx-2">|</span>
								Consumer: <span className="font-medium text-foreground">{consumerSystem || "-"}</span>
							</div>
							<div className="flex items-center gap-2">
								<Button type="button" variant="outline" onClick={handleBackToSetup}>
									Back to ICD Setup
								</Button>
								<Button type="button" variant="ghost" onClick={handleClear}>
									Clear ICD Data
								</Button>
							</div>
						</div>
					</div>

					{viewMode === "selection" ? (
					<TableInspectorTools
						selectedFile={selectedFile}
						hasScannedTables={hasScannedTables}
						isLoadingTables={isLoadingTables}
						tableCandidates={tableCandidates}
						selectedTableIndexes={selectedTableIndexes}
						isProcessing={isProcessing}
						onToggleTable={handleToggleTable}
						onRescan={() => handleGetTables()}
						onGetDataElements={handleGetDataElements}
					/>
				) : (
					<ModelResultsEditor
						documentName={selectedFile?.name ?? ""}
						rows={modelResultRows}
						batchOutputs={modelBatchOutputs}
						isSaving={isSaving}
						dsaOptions={dsaOptions}
						onChangeCell={handleChangeResultCell}
						onAddRow={handleAddResultRow}
						onDeleteRow={handleDeleteResultRow}
						onResetRow={handleResetResultRow}
						onRestoreLastDeleted={handleRestoreDeletedRow}
						deletedRowCount={deletedResultRows.length}
						modifiedRowIds={modifiedRowIds}
						onBackToSelection={handleBackToSelection}
						onDownloadJson={handleDownloadResultsJson}
					/>
					)}
				</div>
			)}
		</div>
	);
};
