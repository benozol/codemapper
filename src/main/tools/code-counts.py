#!/usr/bin/env python3
import pandas as pd
from pathlib import Path
import os
import argparse
import tkinter as tk
from tkinter import filedialog, messagebox
import sys
from glob import glob

HELP = """
This program connects the code counts resulting from the event fingerprinting
with the code sets that were generated with CodeMapper. It runs on the code
counts on all events from one database at once.

Please select the files and click `Run`. Obligatory variables are marked with
stars.

Vocabularies*:
The CodeMapper/UMLS vocabularies for the database (space separated entries,
e.g. "ICD9 ICD9CM MTHICD9" without quotes)

Code counts*:
A CSV file with the code counts generated by event fingerprinting (columns
`Database`, `EventType`, `EventCode`, `Count`, `FirstCount`).

Code names:
A CSV file with local code names for the database (columns `Code`, `Name`)

Mappings*:
The code sets for all events as generated by CodeMapper in XLS files.

Output*:
The output XLS file.

Questions to b.becker@erasmusmc.nl
""".strip()


EXCLUDE_RCD2_CODES = ['_DRUG', '_NONE', '2....']


def get_code_names(csv_filename):
    """ Read code names, including DB-specific codes. Should be a CSV with columns
    `Code` and `Name`."""
    if csv_filename:
        return (
            pd.read_csv(csv_filename, None, engine='python')
            .assign(Name=lambda df: df.Name.fillna('??'))
            [['Code', 'Name']]
        )
    else:
        return pd.DataFrame(columns=['Code', 'Name'])


def get_code_counts(csv_filename):
    """Read and adapt the code counts that were generated in event fingerprinting.
    Must be a TSV with columns `Database`, `EventType`, `EventCode`, `Count`,
    `FirstCount`."""
    return (
        pd.read_csv(csv_filename, sep=None, engine='python')
        .rename(columns={
            'EventCode': 'Code',
            'EventType': 'Event'
        })
        [['Event', 'Code', 'Count', 'FirstCount']]
    )


def get_mappings(comap_xls_files, vocabularies):
    return pd.concat([
        (pd.read_excel(filename, sheetname='Codes')
         .rename(columns={'Coding system': 'Vocabulary'}))
         .pipe(lambda df: df[df.Vocabulary.isin(vocabularies)])
         .pipe(lambda df: df[df.Code != '-'])
         .pipe(lambda df: df[(df.Vocabulary != 'RCD2') | ~df.Code.isin(EXCLUDE_RCD2_CODES)])
         .drop_duplicates('Code')
         .assign(Event=Path(filename).name[:-4])
        [['Event', 'Vocabulary', 'Concept', 'Concept name', 'Code', 'Code name']]
        for filename in comap_xls_files
    ]).reset_index(drop=True)


def get_mapped_codes(code_counts, mappings, norm_prefix, norm_dots, norm_case):
    def norm(code):
        code0 = code
        if norm_prefix and code.startswith(norm_prefix):
            code = code[len(norm_prefix):]
        if norm_dots:
            code = code.replace('.', '')
        if norm_case:
            code = code.lower()
        return code
    mapped = [(row.Event, row.Code) for _, row in mappings.iterrows()]
    def search_prefix(row):
        # Search mapped code that is a prefix
        event, code = row.Event, row.Code
        for N in range(0, len(code)):
            prefix = code[:-N] if N else code
            for e, c in mapped:
                if e == event and norm(c) == norm(prefix):
                    return c
        return '???'
    return code_counts.apply(search_prefix, axis=1)


def get_count_mapped(mappings, code_counts, code_names, mapped_codes):
    code_counts = (
        code_counts
        .rename(columns={
            'Code': 'Extracted code'
        })
        .assign(Code=mapped_codes)
    )
    code_names = (
        code_names
        .rename(columns={
            'Code': 'Extracted code',
            'Name': 'Extracted code name'
        })
    )
    res = (
        mappings
        .merge(code_counts, how='outer', on=['Event', 'Code'])
        .merge(code_names, how='left', on='Extracted code')
    )
    res['Extracted code']      = res['Extracted code'].fillna('-')
    res['Extracted code name'] = res['Extracted code name'].fillna('')
    res['Count']               = res['Count'].fillna(0)
    res['FirstCount']          = res['FirstCount'].fillna(0)
    res.loc[res['Code'] == res['Extracted code'], 'Note'] = 'Generated and extracted'
    res.loc[res['Code'] != res['Extracted code'], 'Note'] = 'Generated and extracted similar'
    res.loc[res['Code'] == '???'                , 'Note'] = 'Not generated'
    res.loc[res['Extracted code'] == '-'        , 'Note'] = 'Not extracted'
    res.sort_values(['Event', 'Note', 'Vocabulary', 'Code'], inplace=True)
    return res


def run(vocabularies, code_counts_csv, code_names_csv, comap_xls_files, norm_prefix, norm_dots, norm_case, output_xls):
    # Event × Vocabulary × Code × ...
    mappings     = get_mappings(comap_xls_files, vocabularies)
    # Code × Name
    code_names   = get_code_names(code_names_csv)
    # Event × Code × Count × FirstCount
    code_counts  = get_code_counts(code_counts_csv)
    # Code
    mapped_codes = get_mapped_codes(code_counts, mappings, norm_prefix, norm_dots, norm_case)
    # Event × Vocabulary × Code × Count × FirstCount × Extracted code × Note × ...
    count_mapped = get_count_mapped(mappings, code_counts, code_names, mapped_codes)
    row_order = [
        'Event', 'Vocabulary', 'Concept', 'Concept name',
        'Code', 'Code name', 'Extracted code', 'Extracted code name',
        'Count', 'FirstCount', 'Note'
    ]
    res = count_mapped[row_order]
    with pd.ExcelWriter(output_xls) as writer:
        res.to_excel(writer, 'Codes', index=False)
        parameters = pd.DataFrame.from_records([
            ('Vocabularies', ' '.join(vocabularies)),
            ('Code counts', code_counts_csv),
            ('Code names', code_names_csv),
            ('Mappings', ', '.join(comap_xls_files)),
            ('Norm prefix', norm_prefix),
            ('Norm dots', 'Yes' if norm_dots else 'No'),
            ('Norm case', 'Yes' if norm_case else 'No'),
        ], columns=['Parameter', 'Value'])
        parameters.to_excel(writer, 'Parameters', index=False)


def main_command_line():
    parser = argparse.ArgumentParser()
    parser.add_argument('--vocabularies', metavar='STR', nargs="*", required=True,
                        help="CodeMapper/UMLS vocabularies for the database")
    parser.add_argument('--code-counts', metavar='FILE', required=True,
                        help="A CSV file with the code counts generated in event fingerprinting")
    parser.add_argument('--code-names', metavar='FILE',
                        help="A CSV file with local code names for the database")
    parser.add_argument('--mappings', metavar='FILE', nargs="*", required=True,
                        help="A list of XLS file containing the event mappings generated by CodeMapper")
    parser.add_argument('--norm-prefix', metavar='STR',
                        help="A prefix that is ignored when comparing codes")
    parser.add_argument('--norm-dots', action='store_true',
                        help="Ignore dots when comparing codes")
    parser.add_argument('--norm-case', action='store_true',
                        help="Ignore case when comparing codes")
    parser.add_argument('--output', metavar='FILE', required=True,
                        help="Output XLS file")
    args = parser.parse_args()
    if len(args.mappings) == 1 and os.path.isdir(args.mappings[0]):
        args.mappings = glob(os.path.join(args.mappings[0], '*.xls'))
        print("Globbed mappings directory", ', '.join(args.mappings))
    run(args.vocabularies, args.code_counts, args.code_names, args.mappings, args.norm_prefix, args.norm_dots, args.norm_case, args.output)


class TkApplication(tk.Frame):

    def __init__(self, master=None):
        super().__init__(master)

        self.master.title("Count generated codes")

        self.code_counts = None
        self.code_names = None
        self.mappings = None
        self.output = None

        self.master.rowconfigure(0, weight=1)
        self.master.columnconfigure(0, weight=1)
        self.grid(sticky='NESW')

        row=0

        (tk.Button(self, text="Help", command=self.help)
         .grid(row=row, column=0, columnspan=3, sticky='NESW'))
        row += 1

        (tk.Label(self, text="Vocabularies*:")
         .grid(row=row, column=0, sticky='NWS'))
        self.vocabularies_entry = tk.Entry(self)
        self.vocabularies_entry.grid(row=row, column=1, columnspan=2, sticky='NESW')
        row += 1

        (tk.Label(self, text="Code counts*:")
         .grid(row=row, column=0, sticky='NWS'))
        self.code_counts_label = tk.Label(self)
        self.code_counts_label.grid(row=row, column=1, sticky='NES')
        (tk.Button(self, text="Choose", command=self.choose_code_counts)
         .grid(row=row, column=2, sticky='NESW'))
        row += 1

        (tk.Label(self, text="Code names:")
         .grid(row=row, column=0, sticky='NWS'))
        self.code_names_label = tk.Label(self)
        self.code_names_label.grid(row=row, column=1, sticky='NES')
        (tk.Button(self, text="Choose", command=self.choose_code_names)
         .grid(row=row, column=2, sticky='NESW'))
        row += 1

        (tk.Label(self, text="Mappings*:")
         .grid(row=row, column=0, sticky='NWS'))
        self.mappings_label = tk.Label(self)
        self.mappings_label.grid(row=row, column=1, sticky='NES')
        (tk.Button(self, text="Choose", command=self.choose_mappings)
         .grid(row=row, column=2, sticky='NESW'))
        row += 1

        (tk.Label(self, text="Output file*:")
         .grid(row=row, column=0, sticky='NWS'))
        self.output_label = tk.Label(self)
        self.output_label.grid(row=row, column=1, sticky='NES')
        (tk.Button(self, text="Choose", command=self.choose_output_file)
         .grid(row=row, column=2, sticky='NESW'))
        row += 1

        (tk.Button(self, text="Run", command=self.run)
         .grid(row=row, column=0, columnspan=3, sticky='NESW'))
        row += 1

    def choose_code_counts(self):
        self.code_counts = filedialog.askopenfilename(parent=self.master,
            filetypes=[('TXT/CSV/TSV files', ('.csv', '.tsv', '.txt')), ('All files', '.*')])
        self.code_counts_label.config(text=Path(self.code_counts).name if self.code_counts else '')

    def choose_code_names(self):
        self.code_names = filedialog.askopenfilename(parent=self.master,
            filetypes=[('TXT/CSV/TSV files', ('.csv', '.tsv', '.txt')), ('All files', '.*')])
        self.code_names_label.config(text=Path(self.code_names).name if self.code_names else '')

    def choose_mappings(self):
        self.mappings = filedialog.askopenfilenames(parent=self.master,
            filetypes=[('XLS files', '.xls'), ('All files', '.*')])
        text = ''
        if self.mappings:
            text = ', '.join(Path(f).name[:-4] for f in self.mappings)
        self.mappings_label.config(text=text)

    def choose_output_file(self):
        self.output = filedialog.asksaveasfilename(parent=self.master,
            defaultextension='.xls', filetypes=[('XLS files', '.xls')])
        self.output_label.config(text=Path(self.output).name if self.output else '')

    def help(self):
        messagebox.showinfo("Code counts to mappings", HELP)

    def get_vocabularies(self):
        return self.vocabularies_entry.get().split(' ')

    def run(self):
        vocabularies = self.get_vocabularies()
        if vocabularies and self.code_counts and self.mappings and self.output:
            if os.path.isfile(self.output) and \
               not messagebox.askyesno("File exists", "File {} already exists. Overwrite?"
                                       .format(self.output)):
                return
            run(vocabularies, self.code_counts, self.code_names, self.mappings, self.output)
            messagebox.showinfo("Script finished", "Script finished")
        else:
            messagebox.showwarning("Missing parameters", (
                "Parameters are missing: \n"
                "Vocabularies: {}\n"
                "Code counts: {}\n"
                "Mappings: {}\n"
                "Output: {}"
            ).format(vocabularies, self.code_counts or '-', self.mappings or '-', self.output or '-'))


def main_tk():
    master = tk.Tk()
    app = TkApplication(master=master)
    app.mainloop()

if __name__ == '__main__':
    if len(sys.argv) > 1:
        main_command_line()
    else:
        main_tk()
