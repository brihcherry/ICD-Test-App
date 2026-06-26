import { FileText, Upload } from "lucide-react";
import { useEffect, useRef, useState, type DragEvent } from "react";
import { Button } from "./ui/button";

const MAX_FILE_SIZE_MB = 25;

const isWordDocument = (file: File) => {
	const validMimeTypes = [
		"application/msword",
		"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
	];
	const lowerName = file.name.toLowerCase();
	const hasWordExtension = lowerName.endsWith(".doc") || lowerName.endsWith(".docx");
	return validMimeTypes.includes(file.type) || hasWordExtension;
};

export const formatFileSize = (bytes: number) => {
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

type Props = {
	dictionaryLoaded: boolean;
	selectedFile: File | null;
	isLoadingTables: boolean;
	maxFileSizeBytes: number;
	onSelectIcdFile: (file: File) => void;
	onClear: () => void;
	onChooseTables: () => void;
};

export const DocumentUploadTools = ({
	dictionaryLoaded,
	selectedFile,
	isLoadingTables,
	maxFileSizeBytes,
	onSelectIcdFile,
	onClear,
	onChooseTables,
}: Props) => {
	const fileInputRef = useRef<HTMLInputElement | null>(null);
	const [isDragActive, setIsDragActive] = useState(false);

	// Reset file input when selection is cleared externally
	useEffect(() => {
		if (!selectedFile && fileInputRef.current) fileInputRef.current.value = "";
	}, [selectedFile]);

	const handleFileSelected = (file?: File | null) => {
		if (!file) return;
		if (!isWordDocument(file)) return; // validation handled by parent via toast
		if (file.size > maxFileSizeBytes) return;
		onSelectIcdFile(file);
	};

	const handleDrop = (event: DragEvent<HTMLLabelElement>) => {
		event.preventDefault();
		setIsDragActive(false);
		handleFileSelected(event.dataTransfer.files?.[0]);
	};

	return (
		<div className="h-full overflow-y-auto bg-muted/30 p-5">
			<div className="space-y-8">

				{/* Section: Data Dictionary Status */}
				<div>
					<p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">Data Dictionary</p>
					<p className={`mt-3 flex items-center gap-2 rounded-lg border px-3 py-2 text-sm ${
						dictionaryLoaded
							? "border-green-200/50 bg-green-50/50 text-green-700"
							: "border-border text-muted-foreground"
					}`}>
						{dictionaryLoaded ? (
							<><span>✓</span><span>Data Subject Areas loaded</span></>
						) : (
							<span>Loading data dictionary...</span>
						)}
					</p>
				</div>

				<div className="border-t" />

				{/* Section: ICD Document */}
				<div>
					<p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">ICD Document</p>
					<label
						htmlFor="word-file"
						onDragOver={(event) => {
							event.preventDefault();
							setIsDragActive(true);
						}}
						onDragLeave={() => setIsDragActive(false)}
						onDrop={handleDrop}
						className={`mt-3 flex cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed px-4 py-8 text-center transition-colors ${
							isDragActive ? "border-primary bg-primary/5" : "border-border hover:border-primary/60"
						}`}
					>
						<Upload className="mb-2 h-8 w-8 text-muted-foreground" />
						<p className="text-sm font-medium">Drop file here</p>
						<p className="mt-0.5 text-xs text-muted-foreground">or click to browse</p>
						<p className="mt-2 text-xs text-muted-foreground">.doc / .docx · max {MAX_FILE_SIZE_MB} MB</p>
					</label>
					<input
						id="word-file"
						type="file"
						accept=".doc,.docx,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
						className="hidden"
						ref={fileInputRef}
						onChange={(event) => handleFileSelected(event.target.files?.[0])}
					/>
					{selectedFile && (
						<Button
							type="button"
							variant="outline"
							className="mt-3 w-full"
							onClick={onClear}
							disabled={!selectedFile}
						>
							Clear
						</Button>
					)}
				</div>

				{/* Section: Selected File status + Choose Tables */}
				{selectedFile && (
					<>
						<div className="border-t" />
						<div>
							<p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">Selected File</p>
							<div className="mt-2 flex items-start gap-2">
								<FileText className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
								<div>
									<p className="text-sm font-medium leading-tight break-all">{selectedFile.name}</p>
									<p className="mt-0.5 text-xs text-muted-foreground">{formatFileSize(selectedFile.size)}</p>
								</div>
							</div>
							<Button
								type="button"
								className="mt-4 w-full"
								onClick={onChooseTables}
								disabled={isLoadingTables}
							>
								{isLoadingTables ? "Scanning..." : "Inspect Tables"}
							</Button>
						</div>
					</>
				)}

			</div>
		</div>
	);
};
