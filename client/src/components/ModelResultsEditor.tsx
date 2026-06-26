import { Fragment, useEffect, useRef, useState } from "react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import type { ModelBatchOutput, ModelResultRow } from "./types";

type Props = {
	documentName: string;
	rows: ModelResultRow[];
	batchOutputs: ModelBatchOutput[];
	isSaving: boolean;
	dsaOptions: string[];
	onChangeCell: (id: string, field: "dataElement" | "description" | "variableType" | "fieldLength" | "delimited" | "valueRange" | "dataSubjectArea" | "confidence", value: string) => void;
	onAddRow: () => void;
	onDeleteRow: (id: string) => void;
	onResetRow: (id: string) => void;
	onRestoreLastDeleted: () => void;
	deletedRowCount: number;
	modifiedRowIds: Set<string>;
	onBackToSelection: () => void;
	onDownloadJson: () => void;
};

export const ModelResultsEditor = ({
	documentName,
	rows,
	batchOutputs,
	isSaving,
	dsaOptions,
	onChangeCell,
	onAddRow,
	onDeleteRow,
	onResetRow,
	onRestoreLastDeleted,
	deletedRowCount,
	modifiedRowIds,
	onBackToSelection,
	onDownloadJson,
}: Props) => {
	const [openAlternatesRowId, setOpenAlternatesRowId] = useState<string | null>(null);
	const alternatesPopupRef = useRef<HTMLDivElement | null>(null);
	const cellInputClass = "h-8 w-full rounded-none border-0 bg-transparent px-2 py-1 text-sm shadow-none focus-visible:ring-0 focus-visible:outline-none";
	const cellSelectClass = "h-8 w-full rounded-none border-0 bg-transparent px-2 py-1 text-sm shadow-none focus:outline-none focus:ring-0";

	const toggleAlternatesPopup = (id: string) => {
		setOpenAlternatesRowId((current) => (current === id ? null : id));
	};

	useEffect(() => {
		if (!openAlternatesRowId) return;

		const onPointerDown = (event: MouseEvent) => {
			const popupEl = alternatesPopupRef.current;
			if (!popupEl) return;
			const target = event.target;
			if (target instanceof Node && !popupEl.contains(target)) {
				setOpenAlternatesRowId(null);
			}
		};

		const onKeyDown = (event: KeyboardEvent) => {
			if (event.key === "Escape") {
				setOpenAlternatesRowId(null);
			}
		};

		document.addEventListener("mousedown", onPointerDown);
		document.addEventListener("keydown", onKeyDown);

		return () => {
			document.removeEventListener("mousedown", onPointerDown);
			document.removeEventListener("keydown", onKeyDown);
		};
	}, [openAlternatesRowId]);

	useEffect(() => {
		if (!openAlternatesRowId) return;
		const activeRow = rows.find((row) => row.id === openAlternatesRowId);
		if (!activeRow || (!activeRow.dataSubjectAreaAlt1 && !activeRow.dataSubjectAreaAlt2)) {
			setOpenAlternatesRowId(null);
		}
	}, [openAlternatesRowId, rows]);

	return (
		<div className="flex-1 overflow-y-auto p-4 md:p-6">
			<div className="flex flex-wrap items-center justify-between gap-3">
				<div>
					<h2 className="text-base font-semibold">Model Results Editor</h2>
					<p className="mt-1 text-sm text-muted-foreground">
						Review and edit model-assigned subject areas before downloading JSON.
					</p>
				</div>
				<div className="flex flex-wrap gap-2">
					<Button type="button" variant="outline" onClick={onBackToSelection}>
						Back to Selection
					</Button>
					<Button type="button" variant="outline" onClick={onAddRow}>
						Add Row
					</Button>
					<Button type="button" variant="outline" onClick={onRestoreLastDeleted} disabled={deletedRowCount === 0}>
						Restore Last Deleted
					</Button>
					<Button type="button" onClick={onDownloadJson} disabled={rows.length === 0 || isSaving}>
						{isSaving ? "Preparing Download..." : "Download Raw JSON"}
					</Button>
				</div>
			</div>
			<div className="mt-4 grid gap-3 rounded-lg border bg-muted/20 p-3 text-xs text-muted-foreground md:grid-cols-2">
				<p>Document: <span className="font-medium text-foreground">{documentName || "Unknown"}</span></p>
				<p>Rows: <span className="font-medium text-foreground">{rows.length}</span></p>
			</div>

			<div className="mt-4 max-h-[65vh] overflow-auto border">
				<table className="w-full min-w-[1650px] text-sm [&_th:not(:last-child)]:border-r [&_th:not(:last-child)]:border-border/80 [&_td:not(:last-child)]:border-r [&_td:not(:last-child)]:border-border/80">
					<thead>
						<tr className="sticky top-0 z-10 border-b border-border/80 bg-muted/95 backdrop-blur supports-[backdrop-filter]:bg-muted/80">
							<th className="px-3 py-2 text-left font-medium text-foreground">Data Element</th>
							<th className="px-3 py-2 text-left font-medium text-muted-foreground">Description</th>
							<th className="px-3 py-2 text-left font-medium text-muted-foreground">Data Subject Area (Primary)</th>
							<th className="px-3 py-2 text-left font-medium text-muted-foreground">Variable Type</th>
							<th className="px-3 py-2 text-left font-medium text-muted-foreground">Field Length</th>
							<th className="px-3 py-2 text-left font-medium text-muted-foreground">Delimited</th>
							<th className="px-3 py-2 text-left font-medium text-muted-foreground">Value Range</th>
							<th className="px-3 py-2 text-left font-medium text-muted-foreground">Confidence</th>
							<th className="w-36 px-3 py-2 text-right font-medium text-muted-foreground">Actions</th>
						</tr>
					</thead>
					<tbody>
						{rows.length === 0 ? (
							<tr>
								<td colSpan={9} className="px-3 py-8 text-center text-muted-foreground">
									No parsed rows yet. Run table processing first or add rows manually.
								</td>
							</tr>
						) : (
							rows.map((row) => {
								const confidenceKey = row.confidence.trim().toLowerCase();
								const isModified = modifiedRowIds.has(row.id);
								const rowBg =
									confidenceKey === "high" ? "bg-green-50/60" :
									confidenceKey === "medium" ? "bg-yellow-50/60" :
									confidenceKey === "low" ? "bg-red-50/60" :
									"";
								const hasAlternates = !!(row.dataSubjectAreaAlt1 || row.dataSubjectAreaAlt2);
								const isAlternatesPopupOpen = openAlternatesRowId === row.id;
								return (
								<Fragment key={row.id}>
									<tr className={`border-b border-border/80 hover:bg-muted/30 ${rowBg}`}>
										<td className="border-r px-3 py-2 align-top">
											<div className="flex items-center gap-2">
												<span
													className={`h-2.5 w-2.5 rounded-full ${isModified ? "bg-amber-500" : "bg-transparent"}`}
													title={isModified ? "Row modified" : ""}
													aria-hidden="true"
												/>
												<Input
													value={row.dataElement}
													onChange={(event) => onChangeCell(row.id, "dataElement", event.target.value)}
													placeholder="Data element"
													className={`${cellInputClass} font-semibold`}
												/>
											</div>
										</td>
										<td className="px-3 py-2 align-top">
											<Input
												value={row.description}
												onChange={(event) => onChangeCell(row.id, "description", event.target.value)}
												placeholder="Definition / description"
												className={cellInputClass}
											/>
										</td>
										<td className="px-3 py-2 align-top">
											<div className="relative flex items-center gap-2">
												<div className="min-w-0 flex-1">
													{dsaOptions.length > 0 ? (
														<select
															value={dsaOptions.find((o) => o.toLowerCase() === row.dataSubjectArea.toLowerCase()) ?? ""}
															onChange={(event) => onChangeCell(row.id, "dataSubjectArea", event.target.value)}
															className={cellSelectClass}
														>
															<option value="">-- Select --</option>
															{dsaOptions.map((name) => (
																<option key={name} value={name}>{name}</option>
															))}
														</select>
													) : (
														<Input
															value={row.dataSubjectArea}
															onChange={(event) => onChangeCell(row.id, "dataSubjectArea", event.target.value)}
															placeholder="Subject area"
															className={cellInputClass}
														/>
													)}
												</div>
												<Button
													type="button"
													variant="outline"
													size="sm"
													disabled={!hasAlternates}
													onClick={() => toggleAlternatesPopup(row.id)}
													aria-expanded={isAlternatesPopupOpen}
													aria-haspopup="dialog"
												>
													Alternates
												</Button>
												{isAlternatesPopupOpen && hasAlternates && (
													<div
														ref={alternatesPopupRef}
														className="absolute right-0 top-10 z-20 min-w-[220px] rounded-md border border-border bg-background p-2 shadow-lg"
														role="dialog"
														aria-label="Alternate data subject areas"
													>
														<p className="px-1 text-xs font-medium text-muted-foreground">
															Set primary from alternates
														</p>
														<div className="mt-2 flex flex-col gap-1">
															{row.dataSubjectAreaAlt1 && (
																<Button
																	type="button"
																	variant="outline"
																	size="sm"
																	className="justify-start"
																	onClick={() => {
																		onChangeCell(row.id, "dataSubjectArea", row.dataSubjectAreaAlt1);
																		setOpenAlternatesRowId(null);
																	}}
																>
																	{row.dataSubjectAreaAlt1}
																</Button>
															)}
															{row.dataSubjectAreaAlt2 && (
																<Button
																	type="button"
																	variant="outline"
																	size="sm"
																	className="justify-start"
																	onClick={() => {
																		onChangeCell(row.id, "dataSubjectArea", row.dataSubjectAreaAlt2);
																		setOpenAlternatesRowId(null);
																	}}
																>
																	{row.dataSubjectAreaAlt2}
																</Button>
															)}
														</div>
														<Button
															type="button"
															variant="ghost"
															size="sm"
															className="mt-2 w-full"
															onClick={() => setOpenAlternatesRowId(null)}
														>
															Close
														</Button>
													</div>
												)}
											</div>
										</td>
										<td className="px-3 py-2 align-top">
											<select
												value={row.variableType}
												onChange={(event) => onChangeCell(row.id, "variableType", event.target.value)}
												className={cellSelectClass}
											>
												<option value="">-- Select --</option>
												<option value="Character">Character</option>
												<option value="Numeric">Numeric</option>
											</select>
										</td>
										<td className="px-3 py-2 align-top">
											<Input
												value={row.fieldLength}
												onChange={(event) => onChangeCell(row.id, "fieldLength", event.target.value)}
												placeholder="50 / 100"
												className={cellInputClass}
											/>
										</td>
										<td className="px-3 py-2 align-top">
											<Input
												value={row.delimited}
												onChange={(event) => onChangeCell(row.id, "delimited", event.target.value)}
												placeholder="Yes / No / delimiter char"
												className={cellInputClass}
											/>
										</td>
										<td className="px-3 py-2 align-top">
											<Input
												value={row.valueRange}
												onChange={(event) => onChangeCell(row.id, "valueRange", event.target.value)}
												placeholder="1-999 / allowed values"
												className={cellInputClass}
											/>
										</td>
										<td className="px-3 py-2 align-top">
											<select
												value={row.confidence}
												onChange={(event) => onChangeCell(row.id, "confidence", event.target.value)}
												className={cellSelectClass}
											>
												<option value="">-- Select --</option>
												<option value="High">High</option>
												<option value="Medium">Medium</option>
												<option value="Low">Low</option>
											</select>
										</td>
										<td className="px-3 py-2 align-top text-right">
											<div className="flex justify-end gap-1">
												<Button
													type="button"
													variant="ghost"
													size="sm"
													onClick={() => onResetRow(row.id)}
												>
													Reset
												</Button>
												<Button
													type="button"
													variant="ghost"
													size="sm"
													className="text-destructive hover:text-destructive"
													onClick={() => onDeleteRow(row.id)}
												>
													Delete
												</Button>
											</div>
										</td>
									</tr>
								</Fragment>
								);
							})
						)}
					</tbody>
				</table>
			</div>

			<details className="mt-4 rounded-lg border bg-muted/20 p-3">
				<summary className="cursor-pointer text-sm font-medium text-foreground">
					Batch Parsing Diagnostics
				</summary>
				<div className="mt-3 space-y-3">
					{batchOutputs.length === 0 ? (
						<p className="text-xs text-muted-foreground">No batch outputs captured.</p>
					) : (
						batchOutputs.map((batch) => (
							<div key={batch.batchNumber} className="rounded border bg-background p-3 text-xs">
								<p className="font-medium text-foreground">
									Batch {batch.batchNumber}: {batch.parsedRowCount} parsed row(s)
								</p>
								{batch.warnings.length > 0 ? (
									<ul className="mt-1 list-disc pl-5 text-amber-700">
										{batch.warnings.map((warning, index) => (
											<li key={index}>{warning}</li>
										))}
									</ul>
								) : null}
								<pre className="mt-2 max-h-44 overflow-auto rounded border bg-muted/30 p-2 whitespace-pre-wrap">
									{batch.rawOutput || "(empty output)"}
								</pre>
							</div>
						))
					)}
				</div>
			</details>
		</div>
	);
};
