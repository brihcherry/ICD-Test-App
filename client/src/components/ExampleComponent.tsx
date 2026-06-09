import { FileText, Upload } from "lucide-react";
import { useMemo, useRef, useState, type DragEvent } from "react";
import { useInsight } from "@semoss/sdk/react";
import { toast } from "sonner";
import { Button } from "./ui/button";
import { Label } from "./ui/label";

const MAX_FILE_SIZE_MB = 25;

type TableCandidate = {
	index: number;
	displayLabel: string;
	firstRowPreview: string[];
	rowCount: number;
	columnCount: number;
};

type GetTablesResponse = {
	parseId?: string;
	tableCount: number;
	tableCandidates: TableCandidate[];
	allTableCount?: number;
	allTableCandidates?: TableCandidate[];
};

const isWordDocument = (file: File) => {
	const validMimeTypes = [
		"application/msword",
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
	];

	const lowerName = file.name.toLowerCase();
	const hasWordExtension =
		lowerName.endsWith(".doc") || lowerName.endsWith(".docx");

	return validMimeTypes.includes(file.type) || hasWordExtension;
};

const formatFileSize = (bytes: number) => {
	if (bytes < 1024) {
		return `${bytes} B`;
	}

	const units = ["KB", "MB", "GB"];
	let value = bytes / 1024;
	let unitIndex = 0;

	while (value >= 1024 && unitIndex < units.length - 1) {
		value /= 1024;
		unitIndex += 1;
	}

	return `${value.toFixed(1)} ${units[unitIndex]}`;
};

export const ExampleComponent = () => {
	const { actions } = useInsight() as {
		actions?: { run: (pixel: string) => Promise<unknown> };
	};
	const fileInputRef = useRef<HTMLInputElement | null>(null);
	const [selectedFile, setSelectedFile] = useState<File | null>(null);
	const [isDragActive, setIsDragActive] = useState(false);
	const [isLoadingTables, setIsLoadingTables] = useState(false);
	const [isExtractingDataElements, setIsExtractingDataElements] = useState(false);
	const [tableCandidates, setTableCandidates] = useState<TableCandidate[]>([]);
	const [allTableCandidates, setAllTableCandidates] = useState<TableCandidate[]>([]);
	const [selectedTableIndexes, setSelectedTableIndexes] = useState<number[]>([]);
	const [showAllTables, setShowAllTables] = useState(false);
	const [parseId, setParseId] = useState<string | null>(null);

	const maxFileBytes = useMemo(() => MAX_FILE_SIZE_MB * 1024 * 1024, []);

	const handleFileSelected = (file?: File | null) => {
		if (!file) {
			return;
		}

		if (!isWordDocument(file)) {
			toast.error("Please upload a .doc or .docx Word document.");
			return;
		}

		if (file.size > maxFileBytes) {
			toast.error(`File is too large. Max size is ${MAX_FILE_SIZE_MB} MB.`);
			return;
		}

		setSelectedFile(file);
		setTableCandidates([]);
		setAllTableCandidates([]);
		setSelectedTableIndexes([]);
		setShowAllTables(false);
		setParseId(null);
	};

	const handleDrop = (event: DragEvent<HTMLLabelElement>) => {
		event.preventDefault();
		setIsDragActive(false);

		const file = event.dataTransfer.files?.[0];
		handleFileSelected(file);
	};

	const handleBrowseClick = () => {
		fileInputRef.current?.click();
	};

	const clearSelection = () => {
		setSelectedFile(null);
		setTableCandidates([]);
		setAllTableCandidates([]);
		setSelectedTableIndexes([]);
		setShowAllTables(false);
		setParseId(null);
		if (fileInputRef.current) {
			fileInputRef.current.value = "";
		}
	};

	const toggleSelectedTable = (tableIndex: number) => {
		setSelectedTableIndexes((current) =>
			current.includes(tableIndex)
				? current.filter((id) => id !== tableIndex)
				: [...current, tableIndex],
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
				resolve(
					commaIndex >= 0 ? reader.result.slice(commaIndex + 1) : reader.result,
				);
			};
			reader.onerror = () => reject(new Error("Unable to read file payload."));
			reader.readAsDataURL(file);
		});

	const findGetTablesPayload = (
		node: unknown,
		depth = 0,
	): GetTablesResponse | null => {
		if (depth > 8 || node == null) {
			return null;
		}

		if (Array.isArray(node)) {
			for (const item of node) {
				const found = findGetTablesPayload(item, depth + 1);
				if (found) {
					return found;
				}
			}
			return null;
		}

		if (typeof node === "object") {
			const asRecord = node as Record<string, unknown>;
			if (Array.isArray(asRecord.tableCandidates)) {
				return {
					parseId: typeof asRecord.parseId === "string" ? asRecord.parseId : undefined,
					tableCount:
						typeof asRecord.tableCount === "number"
							? asRecord.tableCount
							: asRecord.tableCandidates.length,
					tableCandidates: asRecord.tableCandidates as TableCandidate[],
					allTableCount:
						typeof asRecord.allTableCount === "number"
							? asRecord.allTableCount
							: undefined,
					allTableCandidates: Array.isArray(asRecord.allTableCandidates)
						? (asRecord.allTableCandidates as TableCandidate[])
						: undefined,
				};
			}

			for (const value of Object.values(asRecord)) {
				const found = findGetTablesPayload(value, depth + 1);
				if (found) {
					return found;
				}
			}
		}

		return null;
	};

	const handleGetTables = async () => {
		if (!selectedFile) {
			toast.error("Select a .docx file before scanning tables.");
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

			if (!payload) {
				throw new Error("GetTables did not return tableCandidates.");
			}

			setParseId(payload.parseId ?? null);
			setTableCandidates(payload.tableCandidates);
			setAllTableCandidates(payload.allTableCandidates ?? payload.tableCandidates);
			setSelectedTableIndexes([]);
			setShowAllTables(false);
		} catch (error) {
			const message =
				error instanceof Error
					? error.message
					: "Failed to identify document tables.";
			toast.error(message);
		} finally {
			setIsLoadingTables(false);
		}
	};

	const fallbackTableCandidates = allTableCandidates.filter(
		(candidate) => !tableCandidates.some((t) => t.index === candidate.index),
	);
	const noTablesFound = allTableCandidates.length === 0;
	const noFilteredMatches = tableCandidates.length === 0 && allTableCandidates.length > 0;

	const handleGetDataElements = async () => {
		if (selectedTableIndexes.length === 0) {
			toast.error("Select at least one table.");
			return;
		}

		if (!parseId) {
			toast.error("No parse session found. Please re-run \"Get tables\" first.");
			return;
		}

		if (!actions?.run) {
			toast.error("SEMOSS actions are not initialized yet.");
			return;
		}

		setIsExtractingDataElements(true);
		try {
			const tableIndexesStr = selectedTableIndexes.join(",");
			const command = `ExtractDataElements(parseId=${JSON.stringify(parseId)}, tableIndexes=${JSON.stringify(tableIndexesStr)})`;
			await actions.run(command);
			toast.success(
				`Extracted data elements from ${selectedTableIndexes.length} table(s).`,
			);
		} catch (error) {
			const message =
				error instanceof Error
					? error.message
					: "Failed to extract data elements.";
			toast.error(message);
		} finally {
			setIsExtractingDataElements(false);
		}
	};

	return (
		<div className="mx-auto max-w-3xl space-y-6 p-6 md:p-10">
			<div className="space-y-2">
				<h1 className="text-2xl font-semibold tracking-tight md:text-3xl">
					Upload ICD Word Document
				</h1>
				<p className="text-sm text-muted-foreground md:text-base">
					Upload a source Word file, scan all tables with GetTables, and then
					choose the table that contains the ICD data elements.
				</p>
			</div>

			<div className="rounded-xl border bg-card p-5 shadow-sm md:p-6">
				<Label htmlFor="word-file" className="text-sm font-medium">
					Document
				</Label>

				<label
					htmlFor="word-file"
					onDragOver={(event) => {
						event.preventDefault();
						setIsDragActive(true);
					}}
					onDragLeave={() => setIsDragActive(false)}
					onDrop={handleDrop}
					className={`mt-2 flex cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed px-6 py-10 text-center transition-colors ${
						isDragActive
							? "border-primary bg-primary/5"
							: "border-border bg-background hover:border-primary/60"
					}`}
				>
					<Upload className="mb-3 h-10 w-10 text-muted-foreground" />
					<p className="font-medium">Drag and drop your Word file here</p>
					<p className="mt-1 text-sm text-muted-foreground">
						or click to browse
					</p>
					<p className="mt-3 text-xs text-muted-foreground">
						Accepted formats: .doc, .docx (max {MAX_FILE_SIZE_MB} MB)
					</p>
				</label>

				<input
					id="word-file"
					type="file"
					accept=".doc,.docx,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
					className="hidden"
					ref={fileInputRef}
					onChange={(event) => handleFileSelected(event.target.files?.[0])}
				/>

				<div className="mt-4 flex flex-wrap items-center gap-3">
					<Button type="button" onClick={handleBrowseClick}>
						Choose File
					</Button>
					<Button
						type="button"
						onClick={handleGetTables}
						disabled={!selectedFile || isLoadingTables}
					>
						{isLoadingTables ? "Scanning Tables..." : "Get Table Names"}
					</Button>
					<Button
						type="button"
						variant="outline"
						onClick={clearSelection}
						disabled={!selectedFile}
					>
						Clear
					</Button>
				</div>
			</div>

			<div className="rounded-xl border bg-card p-5 shadow-sm md:p-6">
				<h2 className="text-sm font-medium text-muted-foreground">
					Selected File
				</h2>
				{selectedFile ? (
					<div className="mt-3 flex items-start gap-3 rounded-lg border bg-background p-4">
						<FileText className="mt-0.5 h-5 w-5 text-primary" />
						<div className="space-y-1">
							<p className="font-medium leading-tight">{selectedFile.name}</p>
							<p className="text-sm text-muted-foreground">
								{formatFileSize(selectedFile.size)}
							</p>
						</div>
					</div>
				) : (
					<p className="mt-3 text-sm text-muted-foreground">
						No document selected yet.
					</p>
				)}
			</div>

			<div className="rounded-xl border bg-card p-5 shadow-sm md:p-6">
				<h2 className="text-sm font-medium text-muted-foreground">
					Detected Tables
				</h2>
				<p className="mt-2 text-xs text-muted-foreground">
					Table A-# candidates are shown first. You can optionally expand and
					select other tables below.
				</p>
				{noTablesFound ? (
					<p className="mt-3 text-sm text-muted-foreground">
						No tables were found in this document.
					</p>
				) : (
					<>
						{noFilteredMatches ? (
							<p className="mt-3 text-sm text-muted-foreground">
								No Table A-# matches were found. Expand the other tables section
								below.
							</p>
						) : null}

						{tableCandidates.length > 0 ? (
							<div className="mt-3 space-y-3">
								{tableCandidates.map((candidate) => {
									const isSelected = selectedTableIndexes.includes(candidate.index);
									return (
										<button
											key={candidate.index}
											type="button"
											onClick={() => toggleSelectedTable(candidate.index)}
											className={`w-full rounded-lg border p-4 text-left transition-colors ${
												isSelected
													? "border-primary bg-primary/5"
													: "border-border bg-background hover:border-primary/60"
											}`}
										>
											<p className="font-medium">{candidate.displayLabel}</p>
											<p className="mt-1 text-sm text-muted-foreground">
												{candidate.rowCount} rows x {candidate.columnCount} columns
											</p>
											{candidate.firstRowPreview.length > 0 ? (
												<p className="mt-1 text-xs text-muted-foreground">
													Header preview: {candidate.firstRowPreview.join(" | ")}
												</p>
											) : null}
										</button>
									);
								})}
							</div>
						) : null}

						{fallbackTableCandidates.length > 0 ? (
							<div className="mt-3">
								<Button
									type="button"
									variant="outline"
									onClick={() => setShowAllTables((current) => !current)}
								>
									{showAllTables
										? "Hide Other Tables"
										: "Don't See The Right Tables?"}
								</Button>
							</div>
						) : null}

						{showAllTables ? (
							<div className="mt-3 space-y-3">
								<p className="text-sm font-medium text-muted-foreground">
									Other Detected Tables
								</p>
								{fallbackTableCandidates.map((candidate) => {
									const isSelected = selectedTableIndexes.includes(candidate.index);
									return (
										<button
											key={candidate.index}
											type="button"
											onClick={() => toggleSelectedTable(candidate.index)}
											className={`w-full rounded-lg border p-4 text-left transition-colors ${
												isSelected
													? "border-primary bg-primary/5"
													: "border-border bg-background hover:border-primary/60"
											}`}
										>
											<p className="font-medium">{candidate.displayLabel}</p>
											<p className="mt-1 text-sm text-muted-foreground">
												{candidate.rowCount} rows x {candidate.columnCount} columns
											</p>
											{candidate.firstRowPreview.length > 0 ? (
												<p className="mt-1 text-xs text-muted-foreground">
													Header preview: {candidate.firstRowPreview.join(" | ")}
												</p>
											) : null}
										</button>
									);
								})}
							</div>
						) : null}

						<div className="mt-4 flex items-center justify-between gap-3">
							<p className="text-sm text-muted-foreground">
								{selectedTableIndexes.length} table(s) selected
							</p>
							<Button
								type="button"
								onClick={handleGetDataElements}
								disabled={selectedTableIndexes.length === 0 || isExtractingDataElements}
							>
								{isExtractingDataElements
									? "Submitting..."
									: "Get Data Elements"}
							</Button>
						</div>
					</>
				)}
			</div>
		</div>
	);
};
