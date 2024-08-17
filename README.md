# Black & White: Creature Isle modding tools

This is a project under development which aims to build a set of tools to write new challenges (stories) for the game Black & White: Creature Isle.

## Current status

The tool is capable of:
- compile script into a CHL binary file
- decompile a CHL binary file into source scripts
- convert a CHL binary file to assembly language
- convert assembly sources into a CHL binary file

## Setup

You need to install Java 11 or later to run this tool. Download the latest release and unpack it to your favorite location.

Optionally add the extracted folder to the PATH environment variable.

## Usage

This tool supports 2 syntax styles to compile CHL scripts, one similar to the original scripting tool from Lionhead, and a custom one more structured.
To see all the available commands and options run the `chlasm` program without any argument.

### LH style

```batch
chlasm_ci -compile -path "%BWPATH%" -scriptpath "Scripts\CreatureIsles\sources" -inputfile list.txt ..\_challenge.chl
```
`BWPATH` must point to the game folder. Be aware that the output file will be overwritten without prompting, so be sure to backup your original CHL or work in another directory.

To see all the available options run `chlasm -help compile`.

### Structured style

To compile, you have to prepare a project file where you declare the C headers to be included, and the list of source files to compile. The release zip contains a sample project which should be self explanatory. Then open a command prompt and run the command:
```batch
chlasm -compile -p _project.txt -o _challenge.chl
```
Be aware that the output file will be overwritten without prompting, so be sure to backup your original CHL or work in another directory.

To see all the available options run `chlasm -help compile`.

## Next steps

TBD

## License

GPL 3

## Author

Daniels118
