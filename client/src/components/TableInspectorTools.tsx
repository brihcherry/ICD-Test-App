import { Button } from "./ui/button";
import type { TableCandidate } from "./types";

const TablePreview = ({ rows }: { rows: string[][] }) => {
	if (rows.length === 0) return null;
	const [headerRow, ...dataRows] = rows;
	return (
		<div className="mt-3 overflow-x-auto rounded border">
			<table className="w-full text-xs">
				<thead>
					<tr className="border-b bg-muted/60">
						{headerRow.map((cell, i) => (
							<th
								key={i}
								className="px-2 py-1.5 text-left font-medium text-muted-foreground whitespace-nowrap max-w-[160px] truncate"
								title={cell}
							>
								{cell || <span className="italic opacity-40">—</span>}
							</th>
						))}
					</tr>
				</thead>
				<tbody>
					{dataRows.map((row, rowIdx) => (
						<tr key={rowIdx} className="border-b last:border-0 odd:bg-background even:bg-muted/20">
							{row.map((cell, cellIdx) => (
								<td
									key={cellIdx}
									className="px-2 py-1.5 text-muted-foreground whitespace-nowrap max-w-[160px] truncate"
									title={cell}
								>
									{cell || <span className="italic opacity-40">—</span>}
								</td>
							))}
						</tr>
					))}
				</tbody>
			</table>
		</div>
	);
};

const TableCard = ({
	candidate,
	isSelected,
	onToggle,
}: {
	candidate: TableCandidate;
	isSelected: boolean;
	onToggle: () => void;
}) => (
	<button
		type="button"
		onClick={onToggle}
		className={`w-full rounded-lg border p-4 text-left transition-colors ${
			isSelected
				? "border-primary bg-primary/5"
				: "border-border bg-background hover:border-primary/60"
		}`}
	>
		<div className="flex items-center justify-between gap-2">
			<p className="font-medium">{candidate.displayLabel}</p>
			<p className="shrink-0 text-xs text-muted-foreground">
				{candidate.rowCount} rows × {candidate.columnCount} cols
			</p>
		</div>
		<TablePreview rows={candidate.previewRows ?? []} />
	</button>
);

type Props = {
	selectedFile: File | null;
	hasScannedTables: boolean;
	isLoadingTables: boolean;
	tableCandidates: TableCandidate[];
	selectedTableIndexes: number[];
	isProcessing: boolean;
	onToggleTable: (index: number) => void;
	onRescan: () => void;
	onGetDataElements: () => void;
};

export const TableInspectorTools = ({
	selectedFile,
	hasScannedTables,
	isLoadingTables,
	tableCandidates,
	selectedTableIndexes,
	isProcessing,
	onToggleTable,
	onRescan,
	onGetDataElements,
}: Props) => {
	const noTablesFound = hasScannedTables && tableCandidates.length === 0;

	return (
		<div className="flex-1 overflow-y-auto p-4 md:p-6">
			<div className="rounded-xl border bg-card p-5 shadow-sm md:p-6">
				<div className="flex flex-wrap items-center justify-between gap-3">
					<div>
						<h2 className="text-base font-semibold">Table Selection</h2>
						<p className="mt-1 text-sm text-muted-foreground">
							Review table previews and select the tables you want to extract data elements from.
						</p>
					</div>
					{hasScannedTables && (
						<Button
							type="button"
							variant="outline"
							onClick={onRescan}
							disabled={isLoadingTables}
						>
							{isLoadingTables ? "Scanning..." : "Rescan Tables"}
						</Button>
					)}
				</div>

				{/* Empty states */}
				{!selectedFile && (
					<p className="mt-6 text-sm text-muted-foreground">
						Upload an ICD document in the sidebar, then click "Inspect Tables" to get started.
					</p>
				)}

				{selectedFile && !hasScannedTables && !isLoadingTables && (
					<p className="mt-6 text-sm text-muted-foreground">
						Click "Inspect Tables" in the sidebar to scan the document for tables.
					</p>
				)}

				{isLoadingTables && (
					<p className="mt-6 text-sm text-muted-foreground">Scanning document for tables...</p>
				)}

				{noTablesFound && (
					<p className="mt-6 text-sm text-muted-foreground">
						No tables were found in this document.
					</p>
				)}

				{/* Table candidates */}
				{hasScannedTables && !noTablesFound && (
					<>
						{tableCandidates.length > 0 && (
							<div className="mt-4 space-y-4">
								{tableCandidates.map((candidate) => (
									<TableCard
										key={candidate.index}
										candidate={candidate}
										isSelected={selectedTableIndexes.includes(candidate.index)}
										onToggle={() => onToggleTable(candidate.index)}
									/>
								))}
							</div>
						)}

						<div className="mt-6 flex items-center justify-between gap-3 border-t pt-5">
							<p className="text-sm text-muted-foreground">
								{selectedTableIndexes.length} table(s) selected
							</p>
							<Button
								type="button"
								onClick={onGetDataElements}
								disabled={selectedTableIndexes.length === 0 || isProcessing}
							>
								{isProcessing ? "Processing..." : "Get Data Elements from Tables"}
							</Button>
						</div>
					</>
				)}
			</div>
		</div>
	);
};
